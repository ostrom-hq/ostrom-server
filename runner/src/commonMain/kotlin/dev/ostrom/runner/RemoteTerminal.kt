// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A connection to the agent host. */
interface AgentConnection {
    /** From the host. */
    val input: ByteReadChannel

    /** To the host. */
    val output: ByteWriteChannel

    /** Closes the connection. */
    fun close()
}

/**
 * The runner's [TerminalStarter] in a session pod (S2-49): each terminal is started by the agent host, in the agent's
 * container, over a new connection. The connection is retried while the host starts.
 *
 * @param connect Opens a connection to the host.
 * @param attempts Connection attempts before giving up.
 * @param retryDelay Between attempts.
 * @param exitWait How long a stop waits for the host to report the end.
 */
class RemoteTerminalStarter(
    private val connect: suspend () -> AgentConnection,
    private val attempts: Int = 120,
    private val retryDelay: Duration = 500.milliseconds,
    private val exitWait: Duration = 15.seconds,
) : TerminalStarter {
    override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
        val connection = connectWithRetries()
        AgentFrames.write(connection.output, AgentFrame.Start(script, environment, directory))
        return RemoteTerminal(connection, exitWait)
    }

    private suspend fun connectWithRetries(): AgentConnection {
        repeat(attempts - 1) {
            try {
                return connect()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                delay(retryDelay)
            }
        }
        return connect()
    }
}

/**
 * A [Terminal] the agent host runs: output and the end come from the host, keystrokes, sizes and the stop go to it.
 *
 * @param connection The host connection, owned by this terminal.
 * @param exitWait How long [terminate] waits for the host to report the end.
 */
internal class RemoteTerminal(private val connection: AgentConnection, private val exitWait: Duration) : Terminal {
    private val writes = Mutex()
    private val exited = CompletableDeferred<Unit>()

    @Volatile
    private var code: Int? = null

    override val exitCode: Int? get() = code

    override suspend fun read(): ByteArray? {
        while (true) {
            val frame = try {
                AgentFrames.read(connection.input)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A broken connection is an ended terminal: the runner starts the next one.
                null
            }
            when (frame) {
                is AgentFrame.Output -> return frame.bytes
                is AgentFrame.Exit, null -> {
                    code = (frame as? AgentFrame.Exit)?.code
                    exited.complete(Unit)
                    return null
                }
                // Frames only the runner sends: ignored.
                else -> Unit
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = send(AgentFrame.Input(bytes))

    override suspend fun resize(columns: Int, rows: Int) = send(AgentFrame.Resize(columns, rows))

    override suspend fun terminate() {
        send(AgentFrame.Terminate)
        withTimeoutOrNull(exitWait) { exited.await() }
    }

    override fun close() = connection.close()

    /** Sends a frame; a broken connection drops it (the reader then sees the end). */
    private suspend fun send(frame: AgentFrame) {
        try {
            writes.withLock { AgentFrames.write(connection.output, frame) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Unit
        }
    }
}
