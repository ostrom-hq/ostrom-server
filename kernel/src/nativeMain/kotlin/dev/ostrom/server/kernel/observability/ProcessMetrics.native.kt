// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.server.kernel.observability

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.RUSAGE_SELF
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getrusage
import platform.posix.rusage
import platform.posix.sysconf
import platform.posix._SC_PAGESIZE

/** User + system CPU seconds of this process, from `getrusage`. */
internal actual fun processCpuSeconds(): Double = memScoped {
    val usage = alloc<rusage>()
    getrusage(RUSAGE_SELF, usage.ptr)
    val user = usage.ru_utime.tv_sec.toDouble() + usage.ru_utime.tv_usec.toDouble() / MICROS
    val system = usage.ru_stime.tv_sec.toDouble() + usage.ru_stime.tv_usec.toDouble() / MICROS
    user + system
}

/** Resident bytes from `/proc/self/statm` (Linux, where the server runs); null elsewhere. */
internal actual fun processResidentBytes(): Long? {
    val file = fopen("/proc/self/statm", "r") ?: return null
    val line = try {
        memScoped {
            val buffer = allocArray<ByteVar>(LINE)
            fgets(buffer, LINE, file)?.toKString()
        }
    } finally {
        fclose(file)
    }
    val pages = line?.trim()?.split(' ')?.getOrNull(1)?.toLongOrNull() ?: return null
    return pages * sysconf(_SC_PAGESIZE)
}

private const val MICROS = 1_000_000.0
private const val LINE = 128
