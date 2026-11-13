// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

/**
 * One child process with its three pipes, in a session of its own (its process group). Reads and writes suspend and
 * stay cancellable; the native implementation polls its descriptors on the IO dispatcher.
 */
interface ChildProcess {
    /** The process id, leader of its own process group. */
    val pid: Long

    /** Whether it has not exited yet (reaped or not). */
    val isAlive: Boolean

    /** The next bytes of stdout, or null once it is closed. */
    suspend fun readStdout(): ByteArray?

    /** The next bytes of stderr, or null once it is closed. */
    suspend fun readStderr(): ByteArray?

    /**
     * Writes every byte to stdin.
     *
     * @param bytes What to write.
     * @throws ChildIoException When stdin is closed or broken.
     */
    suspend fun write(bytes: ByteArray)

    /** Closes stdin, the graceful end of a stdio server. */
    fun closeStdin()

    /** Waits for the exit and returns its code (128 + the signal for a killed process). */
    suspend fun awaitExit(): Int

    /**
     * Signals the process's whole group.
     *
     * @param force SIGKILL instead of SIGTERM.
     */
    fun signalGroup(force: Boolean)
}

/** A pipe of a child process that is closed or broken. */
class ChildIoException(message: String) : Exception(message)
