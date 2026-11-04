// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable

/** A PTY running a program: in the agent host, or seen from the runner through it. */
interface Terminal {
    /**
     * Reads output, waiting for some.
     *
     * @return Bytes, or null once the program ended and the output is drained.
     */
    suspend fun read(): ByteArray?

    /**
     * Writes keystrokes.
     *
     * @param bytes The keystrokes.
     */
    suspend fun write(bytes: ByteArray)

    /**
     * Resizes the terminal.
     *
     * @param columns Columns.
     * @param rows Rows.
     */
    suspend fun resize(columns: Int, rows: Int)

    /** Asks the program's process group to stop (SIGTERM), then kills it after a grace period. */
    suspend fun terminate()

    /** Frees the PTY. */
    fun close()

    /**
     * The program's exit code, once [read] returned null: 128 + the signal when a signal ended it, null while it runs
     * or when it could not be read.
     */
    val exitCode: Int?
}

/** Starts terminals. */
interface TerminalStarter {
    /**
     * Starts `sh -c script` on a new PTY.
     *
     * @param script The shell script.
     * @param environment Its environment.
     * @param directory Its working directory.
     *
     * @return The terminal.
     */
    suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal
}

/** What the server tells the runner after a heartbeat. */
enum class Instruction { RUN, STOP }

/** The execution was replaced or revoked: this runner must end. */
class ExecutionEndedException(message: String) : Exception(message)

/** A frame from the server's terminal WebSocket. */
sealed interface ServerFrame {
    /** Keystrokes. */
    class Input(val bytes: ByteArray) : ServerFrame

    /** A new size. */
    data class Resize(val columns: Int, val rows: Int) : ServerFrame
}

/** The terminal WebSocket to the server. */
interface TerminalLink {
    /** Frames from the server; closed when the socket closes. */
    val incoming: ReceiveChannel<ServerFrame>

    /**
     * Sends output.
     *
     * @param bytes The output.
     */
    suspend fun send(bytes: ByteArray)

    /** Closes the socket. */
    suspend fun close()
}

/** What the server answered to the launch of the session's agent. */
sealed interface RunnerLaunch {
    /**
     * The launch is open: the agent runs with its token, and its hooks ask the runner for their checks.
     *
     * @property launchId The launch.
     * @property agentToken The agent's token, for the agent's environment only.
     */
    class Opened(val launchId: String, val agentToken: String) : RunnerLaunch {
        /** The token never appears in logs. */
        override fun toString(): String = "Opened(launchId=$launchId)"
    }

    /**
     * The server refused the launch (the organization's policy, a limit): the agent does not start.
     *
     * @property reason Why, as the server says it.
     */
    data class Refused(val reason: String) : RunnerLaunch
}

/**
 * A check the runner ran on the check host, as it reports it (the contract's `ReportCheckRequest`).
 *
 * @property sessionId The agent's session the check guards.
 * @property digest The package's digest.
 * @property nodeId The node of the check.
 * @property scriptId The script.
 * @property scriptSha The SHA-256 of the script the runner ran, computed by the runner.
 * @property exit Its exit code, null when it was stopped at its time limit.
 * @property outcome `passed`, `failed` or `timeout`.
 * @property durationMs How long it ran.
 * @property head The commit it tested, as the hook said.
 * @property sandbox How it was confined.
 * @property outputTail The end of its output.
 */
@Serializable
data class RunnerCheck(
    val sessionId: String,
    val digest: String,
    val nodeId: String,
    val scriptId: String,
    val scriptSha: String,
    val exit: Int?,
    val outcome: String,
    val durationMs: Long,
    val head: String?,
    val sandbox: String,
    val outputTail: String,
)

/** The server, seen from the runner. */
interface ServerApi {
    /**
     * Reports the execution.
     *
     * @param ready Whether the terminal runs.
     * @param stopped Whether the runner stopped the agent.
     * @param lastModelRequestAt The agent's last sign of work (ISO 8601), if any.
     *
     * @return What to do.
     *
     * @throws ExecutionEndedException When the server no longer knows this execution.
     */
    suspend fun heartbeat(ready: Boolean, stopped: Boolean, lastModelRequestAt: String?): Instruction

    /**
     * Opens the terminal WebSocket.
     *
     * @return The link.
     *
     * @throws ExecutionEndedException When the server refuses this execution.
     */
    suspend fun openTerminal(): TerminalLink

    /**
     * Opens the launch of the session's agent.
     *
     * @return The launch, or the server's refusal.
     *
     * @throws ExecutionEndedException When the server no longer knows this execution.
     */
    suspend fun openLaunch(): RunnerLaunch

    /**
     * Reports a check the runner ran: the server records it as attested by the isolated runner.
     *
     * @param check The run.
     *
     * @return Whether the server recorded it.
     */
    suspend fun reportCheck(check: RunnerCheck): Boolean
}
