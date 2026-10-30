// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.channels.ReceiveChannel

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
}
