// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    @Volatile
    private var terminal: Terminal? = null

    @Volatile
    private var lastOutputAt: Instant? = null

    /**
     * Runs until the server says stop or ends the execution.
     *
     * @return The exit code: 0 after a stop, 1 when the execution ended under it.
     */
    suspend fun run(): Int = try {
        coroutineScope {
            val agentEnvironment = SessionScript.environment(environment, config)
            val first = terminals.start(SessionScript.FIRST, agentEnvironment, config.workspace)
            terminal = first
            val terminalJob = launch { superviseTerminal(first, agentEnvironment) }
            val linkJob = launch { relay() }
            val code = heartbeat()
            linkJob.cancel()
            terminalJob.cancel()
            code
        }
    } finally {
        terminal?.close()
    }

    private suspend fun heartbeat(): Int {
        while (true) {
            val instruction = try {
                server.heartbeat(
                    ready = terminal != null,
                    stopped = false,
                    lastModelRequestAt = lastOutputAt?.toString(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExecutionEndedException) {
                log("execution ended: ${error.message}")
                terminal?.terminate()
                return 1
            } catch (error: Exception) {
                log("heartbeat failed: ${error::class.simpleName}")
                null
            }
            if (instruction == Instruction.STOP) {
                log("stopping")
                terminal?.terminate()
                try {
                    server.heartbeat(ready = true, stopped = true, lastModelRequestAt = lastOutputAt?.toString())
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

    private suspend fun superviseTerminal(first: Terminal, agentEnvironment: Map<String, String>) {
        var current = first
        while (true) {
            while (true) {
                val bytes = current.read() ?: break
                lastOutputAt = clock.now()
                output.send(bytes)
            }
            current.close()
            log("terminal ended, starting a shell")
            current = terminals.start(SessionScript.AGAIN, agentEnvironment, config.workspace)
            terminal = current
        }
    }

    private suspend fun relay() = coroutineScope {
        while (isActive) {
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
                    val sender = launch { for (bytes in output) link.send(bytes) }
                    for (frame in link.incoming) {
                        when (frame) {
                            is ServerFrame.Input -> terminal?.write(frame.bytes)
                            is ServerFrame.Resize -> terminal?.resize(frame.columns, frame.rows)
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
