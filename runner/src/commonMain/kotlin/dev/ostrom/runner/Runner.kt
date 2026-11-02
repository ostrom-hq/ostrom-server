// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The runner's loop: one terminal at a time (the session script, then a shell whenever the previous one ends),
 * its output relayed to the server's terminal WebSocket (reconnected after a drop; output produced while
 * disconnected is kept up to [outputBuffer] chunks, oldest dropped), and a heartbeat every [heartbeatInterval]
 * that may tell it to stop.
 *
 * @param config The runner's configuration.
 * @param server The server.
 * @param terminals Starts terminals.
 * @param environment The process environment, passed on to the agent without the runner's private variables.
 * @param clock The clock.
 * @param log Writes a line to the pod's log (never a token, never terminal content).
 * @param heartbeatInterval Between heartbeats.
 * @param reconnectDelay Before reopening a dropped terminal link.
 * @param outputBuffer Output chunks kept while the link is down.
 */
@Suppress("LongParameterList") // Six collaborators and three tunings with defaults, all named at the call sites.
class Runner(
    private val config: RunnerConfig,
    private val server: ServerApi,
    private val terminals: TerminalStarter,
    private val environment: Map<String, String>,
    private val clock: Clock,
    private val log: (String) -> Unit,
    private val heartbeatInterval: Duration = 15.seconds,
    private val reconnectDelay: Duration = 2.seconds,
    private val outputBuffer: Int = 1024,
) {
    private val output = Channel<ByteArray>(outputBuffer, BufferOverflow.DROP_OLDEST)

    /** The terminal in use: the first one, then each shell started after it ended. */
    private class Current(terminal: Terminal) {
        @Volatile
        var terminal: Terminal = terminal
    }

    @Volatile
    private var lastOutputAt: Instant? = null

    /**
     * Runs until the server says stop or ends the execution.
     *
     * @return The exit code: 0 after a stop, 1 when the execution ended under it.
     */
    suspend fun run(): Int {
        val launch = try {
            if (config.governed) openLaunch() else null
        } catch (error: ExecutionEndedException) {
            log("execution ended: ${error.message}")
            return 1
        }
        val agentEnvironment =
            SessionScript.environment(environment, config) - RunnerConfig.RUNNER_SOCKETS + launchVariables(launch)
        val current = Current(terminals.start(SessionScript.FIRST, agentEnvironment, config.workspace))
        return try {
            coroutineScope {
                val terminalJob = launch { superviseTerminal(current, agentEnvironment) }
                val linkJob = launch { relay(current) }
                val code = heartbeat(current)
                linkJob.cancel()
                terminalJob.cancel()
                code
            }
        } finally {
            current.terminal.close()
        }
    }

    private fun lastOutput(): String? = lastOutputAt?.toString()

    /** Opens the agent's launch, a few times while the server cannot be reached; then the agent does not start. */
    private suspend fun openLaunch(): RunnerLaunch {
        repeat(LAUNCH_ATTEMPTS) {
            val launch = try {
                server.openLaunch()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExecutionEndedException) {
                throw error
            } catch (error: Exception) {
                log("launch failed: ${error::class.simpleName}")
                null
            }
            if (launch != null) {
                log("launch $launch")
                return launch
            }
            delay(reconnectDelay)
        }
        return RunnerLaunch.Refused(UNREACHABLE)
    }

    /** What the agent learns of its launch: its id, its token and where its hooks ask for checks, or the refusal. */
    private fun launchVariables(launch: RunnerLaunch?): Map<String, String> = when (launch) {
        null -> emptyMap()
        is RunnerLaunch.Opened -> mapOf(
            "OSTROM_LAUNCH_ID" to launch.launchId,
            "OSTROM_AGENT_TOKEN" to launch.agentToken,
            "OSTROM_URL" to config.serverUrl,
            RunnerConfig.CHECKS_VARIABLE to config.checksSocket,
        )
        is RunnerLaunch.Refused -> mapOf("OSTROM_LAUNCH_REFUSED" to launch.reason)
    }

    private suspend fun heartbeat(current: Current): Int {
        while (true) {
            val instruction = try {
                // Ready from the first beat: the terminal is started before the runner reports.
                server.heartbeat(ready = true, stopped = false, lastModelRequestAt = lastOutput())
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExecutionEndedException) {
                log("execution ended: ${error.message}")
                current.terminal.terminate()
                return 1
            } catch (error: Exception) {
                log("heartbeat failed: ${error::class.simpleName}")
                null
            }
            if (instruction == Instruction.STOP) {
                log("stopping")
                current.terminal.terminate()
                try {
                    server.heartbeat(ready = true, stopped = true, lastModelRequestAt = lastOutput())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log("last heartbeat failed: ${error::class.simpleName}")
                }
                return 0
            }
            delay(heartbeatInterval)
        }
    }

    private suspend fun superviseTerminal(current: Current, agentEnvironment: Map<String, String>) {
        while (true) {
            while (true) {
                val bytes = current.terminal.read() ?: break
                lastOutputAt = clock.now()
                output.send(bytes)
            }
            current.terminal.close()
            log("terminal ended, starting a shell")
            current.terminal = terminals.start(SessionScript.AGAIN, agentEnvironment, config.workspace)
        }
    }

    private suspend fun relay(current: Current) = coroutineScope {
        // Ends by cancellation only, when the heartbeat returns.
        while (true) {
            val link = try {
                server.openTerminal()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log("terminal link failed: ${error::class.simpleName}")
                delay(reconnectDelay)
                continue
            }
            try {
                coroutineScope {
                    val sender = launch { while (true) link.send(output.receive()) }
                    for (frame in link.incoming) {
                        when (frame) {
                            is ServerFrame.Input -> current.terminal.write(frame.bytes)
                            is ServerFrame.Resize -> current.terminal.resize(frame.columns, frame.rows)
                        }
                    }
                    sender.cancel()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log("terminal link dropped: ${error::class.simpleName}")
            } finally {
                withContext(NonCancellable) { link.close() }
            }
            delay(reconnectDelay)
        }
    }
}

/** How many times the launch is asked for before the agent is left out. */
private const val LAUNCH_ATTEMPTS = 10

/** Why the agent does not start when the server never answered the launch. */
private const val UNREACHABLE = "the server could not be reached."
