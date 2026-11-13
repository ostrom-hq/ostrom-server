// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlinx.coroutines.future.await
import java.io.IOException
import java.io.InputStream

/**
 * A [ChildProcess] started by `ProcessBuilder`. Its reads and writes block the calling thread, which is one of the
 * supervisor's IO threads; a process's exit or kill closes its pipes and ends them.
 *
 * @param process The process.
 * @param table Signals its descendants.
 */
class JvmChildProcess(private val process: Process, private val table: ProcessTable) : ChildProcess {
    private val stdout = ByteArray(CHUNK)
    private val stderr = ByteArray(CHUNK)

    override val pid: Long = process.pid()

    override val isAlive: Boolean get() = process.isAlive

    override suspend fun readStdout(): ByteArray? = read(process.inputStream, stdout)

    override suspend fun readStderr(): ByteArray? = read(process.errorStream, stderr)

    private fun read(stream: InputStream, buffer: ByteArray): ByteArray? {
        val count = try {
            stream.read(buffer)
        } catch (_: IOException) {
            -1
        }
        return if (count < 0) null else buffer.copyOf(count)
    }

    override suspend fun write(bytes: ByteArray) {
        try {
            process.outputStream.write(bytes)
            process.outputStream.flush()
        } catch (_: IOException) {
            throw ChildIoException("The process's input is closed.")
        }
    }

    override fun closeStdin() {
        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // Already closed or broken: the end of stdin is what was asked.
        }
    }

    override suspend fun awaitExit(): Int {
        process.onExit().await()
        return process.exitValue()
    }

    // The JDK signals one process at a time: the process and every descendant it still has.
    override fun signalGroup(force: Boolean) {
        (table.descendants(pid) + pid).forEach { table.signal(it, force) }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
