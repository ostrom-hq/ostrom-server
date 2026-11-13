// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.network.selector.SelectorManager
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The runner's credential socket ([RunnerConfig.credentialSocket]), for the agent's `git` helper (`ostrom-runner
 * credential`): one [CredentialRequest] per connection, as one JSON line, answered by one [Credential] line, or `{}`
 * without one. The runner holds the tokens; the agent's container only ever receives the one credential a command
 * asked for, when it asks. A slow server holds a `git` for [TIMEOUT] at most, and at most [MAX_CONCURRENT] requests
 * run at once.
 */
object CredentialSocket {
    /** The largest request line, in bytes. */
    const val MAX_LINE = 16 * 1024

    /** Requests answered at once. */
    const val MAX_CONCURRENT = 8

    /** The longest a request waits for its credential. */
    val TIMEOUT: Duration = 15.seconds

    private val JSON = Json { ignoreUnknownKeys = true }
    private val permits = Semaphore(MAX_CONCURRENT)

    /**
     * Listens and answers each request with [source], until cancelled.
     *
     * @param selector The socket selector.
     * @param path The socket's path.
     * @param source The credentials ([GitHubCredentialSource.credential]).
     * @param log Writes a line to the container's log (never a credential).
     * @param ready Called once the socket listens, with its path (the runner opens it to the agent's uid).
     */
    suspend fun serve(
        selector: SelectorManager,
        path: String,
        source: suspend (CredentialRequest) -> Credential?,
        log: (String) -> Unit,
        ready: (String) -> Unit,
    ) = AgentSocket.listen(selector, path, "credentials", log, ready) { input, output ->
        answer(input, output, source, log)
    }

    /**
     * Answers one connection. A request that cannot be read, a source that fails or takes over [timeout]: no
     * credential.
     *
     * @param input From the helper.
     * @param output To the helper.
     * @param source The credentials.
     * @param log Writes a line to the container's log.
     * @param timeout The longest the source may take.
     */
    suspend fun answer(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        source: suspend (CredentialRequest) -> Credential?,
        log: (String) -> Unit,
        timeout: Duration = TIMEOUT,
    ) {
        val credential = try {
            readSocketLine(input, MAX_LINE)?.let { line ->
                val request = JSON.decodeFromString(CredentialRequest.serializer(), line)
                permits.withPermit { withTimeoutOrNull(timeout) { source(request) } }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            // SerializationException included: a line that is not a request.
            null
        } catch (error: Exception) {
            log("credential source failed: ${error::class.simpleName}")
            null
        }
        val line = credential?.let { JSON.encodeToString(Credential.serializer(), it) } ?: "{}"
        try {
            output.writeByteArray("$line\n".encodeToByteArray())
            output.flush()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("credential reply failed: ${error::class.simpleName}")
        }
    }
}

/**
 * Git's credential helper (`ostrom-runner credential get`), in the agent's container: it asks the runner's
 * [CredentialSocket] for the remote git describes. `store` and `erase` do nothing; without a socket or a credential
 * it prints nothing and succeeds, so git moves on (`GIT_TERMINAL_PROMPT=0` keeps it from asking).
 */
object CredentialHelper {
    /** The longest the helper waits for the runner. */
    val TIMEOUT: Duration = 20.seconds

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * Reads git's input: `key=value` lines, ended by a blank line or the end of the stream.
     *
     * @param lines The input.
     *
     * @return The request.
     */
    fun parse(lines: Sequence<String>): CredentialRequest {
        val values = HashMap<String, String>()
        for (line in lines) {
            if (line.isEmpty()) break
            val separator = line.indexOf('=')
            if (separator > 0) values[line.substring(0, separator)] = line.substring(separator + 1)
        }
        return CredentialRequest(values["protocol"], values["host"], values["path"])
    }

    /**
     * The answer to `get`.
     *
     * @param credential The credential, if any.
     *
     * @return Its lines, or nothing at all without one.
     */
    fun format(credential: Credential?): String =
        if (credential == null) "" else "username=${credential.username}\npassword=${credential.password}\n"

    /**
     * Asks the runner. Nothing is logged: git shows what a helper writes to stderr.
     *
     * @param connect Connects to the runner's socket.
     * @param request The remote.
     * @param timeout The longest to wait.
     *
     * @return The credential, or null when the runner has none or cannot be reached.
     */
    suspend fun ask(
        connect: suspend () -> AgentConnection,
        request: CredentialRequest,
        timeout: Duration = TIMEOUT,
    ): Credential? = try {
        withTimeoutOrNull(timeout) {
            val connection = connect()
            try {
                val line = JSON.encodeToString(CredentialRequest.serializer(), request)
                connection.output.writeByteArray("$line\n".encodeToByteArray())
                connection.output.flush()
                readSocketLine(connection.input, CredentialSocket.MAX_LINE)
                    ?.let { JSON.decodeFromString(Credential.serializer(), it) }
            } finally {
                connection.close()
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // `{}` (no credential) fails to decode as one, like an unreachable or broken runner: nothing.
        null
    }
}
