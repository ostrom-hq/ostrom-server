// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The process table through `ProcessHandle`, and resident memory from `/proc` (or `ps` on a development machine).
 *
 * @param proc The proc file system.
 * @param ps The `ps` command, where there is no [proc].
 * @param psSeconds How long `ps` may take.
 */
class JvmProcessTable(
    private val proc: Path = Path.of("/proc"),
    private val ps: String = "ps",
    private val psSeconds: Long = PS_SECONDS,
) : ProcessTable {
    override fun descendants(pid: Long): List<Long> =
        ProcessHandle.of(pid).map { handle -> handle.descendants().map { it.pid() }.toList() }.orElse(emptyList())

    override fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    override fun signal(pid: Long, force: Boolean) {
        ProcessHandle.of(pid).ifPresent { if (force) it.destroyForcibly() else it.destroy() }
    }

    override fun rssBytes(pids: List<Long>): Long? = when {
        pids.isEmpty() -> null
        Files.isDirectory(proc.resolve("self")) -> pids.sumOf { procRss(it) }
        else -> psRss(pids)
    }

    private fun procRss(pid: Long): Long {
        val line = try {
            Files.readAllLines(proc.resolve("$pid/status")).firstOrNull { it.startsWith("VmRSS:") }
        } catch (_: IOException) {
            null
        } ?: return 0
        return (line.removePrefix("VmRSS:").removeSuffix("kB").trim().toLongOrNull() ?: 0) * KIB
    }

    private fun psRss(pids: List<Long>): Long? = try {
        val process = ProcessBuilder(ps, "-o", "rss=", "-p", pids.joinToString(",")).redirectErrorStream(true).start()
        // The answer is a few lines: it fits the pipe, so the wait comes first and a stuck `ps` cannot block the read.
        if (!process.waitFor(psSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            val lines = process.inputStream.readAllBytes().decodeToString().lines()
            lines.sumOf { it.trim().toLongOrNull() ?: 0L } * KIB
        }
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val KIB = 1024L
        const val PS_SECONDS = 5L
    }
}
