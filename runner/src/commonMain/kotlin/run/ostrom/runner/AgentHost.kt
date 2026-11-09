// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The agent's side of a session pod (`ostrom-runner agent-host`, S2-49): it runs in the agent's container, under the
 * agent's uid, and never sees the runner token. For each connection of the runner it starts the terminal the runner
 * asks for, sends its output back, and passes on keystrokes, sizes and the stop.
 *
 * @param terminals Starts terminals (a real PTY in the pod).
 * @param drain How long a stopped terminal may take to drain its output before the connection ends.
 */
class AgentHost(private val terminals: TerminalStarter, private val drain: Duration = 5.seconds) {
    /**
     * Serves one connection: a [AgentFrame.Start] first, then the terminal until it ends or the runner leaves. The
     * terminal is stopped and freed whatever happens.
     *
     * @param input From the runner.
     * @param output To the runner.
     */
    suspend fun serve(input: ByteReadChannel, output: ByteWriteChannel) {
        val start = AgentFrames.read(input) as? AgentFrame.Start ?: return
        val terminal = terminals.start(start.script, start.environment, start.directory)
        try {
            coroutineScope {
                val pump = launch {
                    while (true) {
                        val bytes = terminal.read() ?: break
                        AgentFrames.write(output, AgentFrame.Output(bytes))
                    }
                    AgentFrames.write(output, AgentFrame.Exit(terminal.exitCode))
                }
                if (relayInput(input, terminal)) {
                    // Stopped by the runner: the output ends once the program is gone, then the EXIT goes out.
                    withTimeoutOrNull(drain) { pump.join() }
                }
                pump.cancel()
            }
        } finally {
            withContext(NonCancellable) {
                terminal.terminate()
                terminal.close()
            }
        }
    }

    /** Passes the runner's frames on; true when it asked to stop, false when it left. */
    private suspend fun relayInput(input: ByteReadChannel, terminal: Terminal): Boolean {
        while (true) {
            when (val frame = AgentFrames.read(input)) {
                is AgentFrame.Input -> terminal.write(frame.bytes)
                is AgentFrame.Resize -> terminal.resize(frame.columns, frame.rows)
                AgentFrame.Terminate -> {
                    terminal.terminate()
                    return true
                }
                null -> return false
                // A second START, or a frame only the host sends: ignored.
                else -> Unit
            }
        }
    }
}
