// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import io.ktor.network.selector.SelectorManager
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * The runner's socket for the agent's hooks ([RunnerConfig.checksSocket]): one [CheckRequest] per connection, as one
 * JSON line, answered by one [CheckReply] line once the check ran. The agent's container reaches it; the check host
 * it runs on, it does not.
 */
object ChecksSocket {
    /** The largest request line, in bytes. */
    const val MAX_LINE = 1 shl 20

    private val JSON = Json {
        ignoreUnknownKeys = true
        // Every field of the reply, so that the hook reads one shape.
        encodeDefaults = true
    }

    /**
     * Listens and runs each request with [checks], until cancelled.
     *
     * @param selector The socket selector.
     * @param path The socket's path.
     * @param run Runs a check ([RunnerChecks.run]).
     * @param log Writes a line to the container's log (never a script nor its output).
     * @param ready Called once the socket listens, with its path (the runner opens it to the agent's uid).
     */
    suspend fun serve(
        selector: SelectorManager,
        path: String,
        run: suspend (CheckRequest) -> CheckReply,
        log: (String) -> Unit,
        ready: (String) -> Unit,
    ) = AgentSocket.listen(selector, path, "checks", log, ready) { input, output -> answer(input, output, run, log) }

    /**
     * Answers one connection: reads the request, runs it, writes the reply. A request that cannot be read is answered
     * as not run.
     *
     * @param input From the hook.
     * @param output To the hook.
     * @param run Runs the check ([RunnerChecks.run]).
     * @param log Writes a line to the container's log.
     */
    suspend fun answer(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        run: suspend (CheckRequest) -> CheckReply,
        log: (String) -> Unit,
    ) {
        val reply = try {
            val line = readSocketLine(input, MAX_LINE)
            if (line == null) {
                CheckReply(started = false, outputTail = "no request.")
            } else {
                val request = JSON.decodeFromString(CheckRequest.serializer(), line)
                run(request).also { log("check ${request.scriptId}: ${it.outcome}, reported ${it.reported}") }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            // SerializationException included: a line that is not a check.
            CheckReply(started = false, outputTail = "${error.message}")
        } catch (error: Exception) {
            log("check failed: ${error::class.simpleName}")
            CheckReply(started = false, outputTail = "the runner could not run the check.")
        }
        try {
            output.writeByteArray((JSON.encodeToString(CheckReply.serializer(), reply) + "\n").encodeToByteArray())
            output.flush()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("check reply failed: ${error::class.simpleName}")
        }
    }
}
