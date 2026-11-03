// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The check host could not be reached: not linked yet, gone, or it did not answer. */
class CheckHostUnreachableException(message: String) : Exception(message)

/**
 * The runner's end of the check host (audit runner 2, R4). The runner listens and the check host connects: the first
 * connection is the link, which the check host opens when it starts, before any script runs. For each check the runner
 * sends a nonce over the link, and the check host opens a new connection that starts with that nonce: a connection
 * without a nonce the runner asked for is closed. A script runs under the check host's uid but never reads the link
 * (the check host is not dumpable and closes its descriptors in the script), so it can neither answer for the check
 * host nor take its place: the side the runner trusts is the runner, under its own uid.
 *
 * @param before Runs before each check asks for its connection (the runner makes sure its socket is still its own).
 * @param wait How long a check waits for the link, while the check host starts.
 * @param answer How long the check host has to open the check's connection.
 * @param exitWait How long a stop waits for the check host to report the end.
 */
@OptIn(ExperimentalUuidApi::class)
class CheckLink(
    private val before: suspend () -> Unit = {},
    private val wait: Duration = 60.seconds,
    private val answer: Duration = 10.seconds,
    private val exitWait: Duration = 15.seconds,
) : TerminalStarter {
    private val link = CompletableDeferred<AgentConnection>()
    private val asked = mutableMapOf<String, CompletableDeferred<AgentConnection>>()
    private val askedLock = Mutex()
    private val linkWrites = Mutex()

    /**
     * Takes a connection the check host opened: the first is the link, kept open; any other is a check's, handed to the
     * check that asked for its nonce, or closed.
     *
     * @param connection The connection, owned by the link from now on.
     */
    suspend fun accept(connection: AgentConnection) {
        if (link.complete(connection)) return
        val nonce = withTimeoutOrNull(answer) { CheckNonces.read(connection.input) }
        hand(nonce?.let { askedLock.withLock { asked.remove(it) } }, connection)
    }

    override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
        val linked = withTimeoutOrNull(wait) { link.await() }
            ?: throw CheckHostUnreachableException("the check host has not linked to the runner.")
        val connection = open(linked) ?: throw CheckHostUnreachableException("the check host did not answer.")
        AgentFrames.write(connection.output, AgentFrame.Start(script, environment, directory))
        return RemoteTerminal(connection, exitWait)
    }

    /** Asks the check host for a check's connection over the link; null when it does not come in time. */
    private suspend fun open(linked: AgentConnection): AgentConnection? {
        before()
        val nonce = Uuid.random().toHexString()
        val check = CompletableDeferred<AgentConnection>()
        askedLock.withLock { asked[nonce] = check }
        var connection: AgentConnection? = null
        try {
            linkWrites.withLock { CheckNonces.write(linked.output, nonce) }
            connection = withTimeoutOrNull(answer) { check.await() }
        } finally {
            withContext(NonCancellable) { askedLock.withLock { asked.remove(nonce) } }
            settle(check, connection)
        }
        return connection
    }

    internal companion object {
        /** Gives a check the connection it asked for; a connection no check waits for any more is closed. */
        fun hand(check: CompletableDeferred<AgentConnection>?, connection: AgentConnection) {
            if (check == null || !check.complete(connection)) connection.close()
        }

        /**
         * Ends a check's wait: a connection that came after it gave up is nobody's, and is closed (the check host would
         * otherwise wait on it for a start that never comes).
         */
        fun settle(check: CompletableDeferred<AgentConnection>, taken: AgentConnection?) {
            if (taken == null && !check.completeExceptionally(CheckHostUnreachableException("late"))) {
                check.getCompleted().close()
            }
        }
    }
}

/**
 * The check host's side of the link (`ostrom-runner check-host`, audit runner 2, R4): it runs, one after the other,
 * the checks the runner asks for over [link], each on a new connection that starts with the runner's nonce.
 *
 * @param host Serves each check's connection (its terminals start the scripts).
 * @param connect Opens a connection to the runner.
 * @param log Writes a line to the container's log.
 */
class CheckHost(
    private val host: AgentHost,
    private val connect: suspend () -> AgentConnection,
    private val log: (String) -> Unit,
) {
    /**
     * Serves the runner until it closes the link.
     *
     * @param link The link, opened before any script runs.
     */
    suspend fun serve(link: AgentConnection) {
        while (true) {
            val nonce = CheckNonces.read(link.input) ?: break
            connectOrNull()?.let { serveCheck(it, nonce) }
        }
    }

    /** A new connection to the runner; null, logged, when it cannot be opened (the runner's check then times out). */
    private suspend fun connectOrNull(): AgentConnection? = try {
        connect()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log("check connection failed: ${error::class.simpleName}")
        null
    }

    /** Opens the check's connection with its nonce, then serves the check on it. */
    private suspend fun serveCheck(connection: AgentConnection, nonce: String) {
        try {
            CheckNonces.write(connection.output, nonce)
            host.serve(connection.input, connection.output)
        } finally {
            connection.close()
        }
    }
}

/** The nonces of the link: 32 hexadecimal characters, no separator. */
internal object CheckNonces {
    private const val LENGTH = 32

    /** Writes [nonce]. */
    suspend fun write(output: ByteWriteChannel, nonce: String) {
        output.writeByteArray(nonce.encodeToByteArray())
        output.flush()
    }

    /** Reads a nonce; null at the end of the stream or on anything but a whole one. */
    suspend fun read(input: ByteReadChannel): String? {
        val bytes = if (input.exhausted()) null else whole(input)
        val text = bytes?.decodeToString().orEmpty()
        return text.takeIf { it.length == LENGTH && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    }

    /** The nonce's bytes; null when the stream ends before them. */
    private suspend fun whole(input: ByteReadChannel): ByteArray? = try {
        input.readByteArray(LENGTH)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
