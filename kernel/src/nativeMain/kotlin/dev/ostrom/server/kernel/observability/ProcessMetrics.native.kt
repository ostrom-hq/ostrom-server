// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.server.kernel.observability

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.clock
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.sysconf
import platform.posix._SC_PAGESIZE

/**
 * User + system CPU seconds of this process, from POSIX `clock()` (all its threads; `getrusage` is not in the
 * Kotlin/Native bindings of Linux). POSIX fixes `CLOCKS_PER_SEC` at one million.
 */
internal actual fun processCpuSeconds(): Double = clock().toDouble() / CLOCKS_PER_SECOND

/** Resident bytes from `/proc/self/statm` (Linux, where the server runs); null elsewhere. */
internal actual fun processResidentBytes(): Long? {
    val pages = readStatm()?.trim()?.split(' ')?.getOrNull(1)?.toLongOrNull()
    return pages?.let { it * sysconf(_SC_PAGESIZE) }
}

/** The first line of `/proc/self/statm`, or null where there is none. */
private fun readStatm(): String? {
    val file = fopen("/proc/self/statm", "r") ?: return null
    return try {
        memScoped {
            val buffer = allocArray<ByteVar>(LINE)
            fgets(buffer, LINE, file)?.toKString()
        }
    } finally {
        fclose(file)
    }
}

private const val CLOCKS_PER_SECOND = 1_000_000.0
private const val LINE = 128
