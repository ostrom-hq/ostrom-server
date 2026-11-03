// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.server.app.isolation

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.opendir
import platform.posix.readdir

/** Kotlin/Native lists `/proc/self/task` and reads each thread's `comm`. */
internal actual fun processThreadNames(): List<String>? {
    val tasks = opendir("/proc/self/task") ?: return null
    val ids = try {
        generateSequence { readdir(tasks) }
            .map { it.pointed.d_name.toKString() }
            .filter { it.first().isDigit() }
            .toList()
    } finally {
        closedir(tasks)
    }
    return ids.map { firstLine("/proc/self/task/$it/comm")?.trim() ?: "?" }
}

// The first line of a file, or null when it cannot be opened (a thread that ended since the listing).
private fun firstLine(path: String): String? {
    val file = fopen(path, "r") ?: return null
    return try {
        memScoped {
            val buffer = allocArray<ByteVar>(LINE)
            fgets(buffer, LINE, file)?.toKString()
        }
    } finally {
        fclose(file)
    }
}

private const val LINE = 64
