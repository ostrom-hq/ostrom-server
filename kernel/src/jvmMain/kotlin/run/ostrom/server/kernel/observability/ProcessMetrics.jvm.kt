// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.observability

import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory

// The shipped runtime (docs/decisions/0001): the figures a container's limit is judged by, read from Linux.

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val BYTES_PER_KIB = 1024L

/** CPU time of the JVM process, from its operating system bean (every thread, user and system). */
internal actual fun processCpuSeconds(): Double =
    (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime /
        NANOS_PER_SECOND

/**
 * The resident set of the process, `VmRSS` of `/proc/self/status` (Linux, where the server runs): heap, metaspace,
 * thread stacks and native buffers, what the container's limit counts. Null elsewhere (macOS has no /proc).
 */
internal actual fun processResidentBytes(): Long? = residentBytes(File("/proc/self/status"))

/** The container's memory limit: cgroup v2 `memory.max`, else cgroup v1 `memory.limit_in_bytes`. */
internal actual fun processMemoryLimit(): String? =
    firstLine(File("/sys/fs/cgroup/memory.max")) ?: firstLine(File("/sys/fs/cgroup/memory/memory.limit_in_bytes"))

/**
 * The `VmRSS` line of a `/proc/<pid>/status` file, in bytes.
 *
 * @param status The file.
 *
 * @return Null when the file or the line is missing or unreadable.
 */
internal fun residentBytes(status: File): Long? = readLines(status)
    ?.firstOrNull { it.startsWith("VmRSS:") }
    ?.removePrefix("VmRSS:")?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()
    ?.let { it * BYTES_PER_KIB }

/**
 * The first line of [file].
 *
 * @return Null where there is none.
 */
internal fun firstLine(file: File): String? = readLines(file)?.firstOrNull()

private fun readLines(file: File): List<String>? = try {
    file.readLines()
} catch (_: IOException) {
    null
}
