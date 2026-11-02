// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.server.app

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

internal actual fun processStatus(): String = readText("/proc/self/status")

internal actual fun threadNames(): List<String> {
    val dir = opendir("/proc/self/task") ?: return emptyList()
    val tids = mutableListOf<String>()
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name.firstOrNull()?.isDigit() == true) tids += name
        }
    } finally {
        closedir(dir)
    }
    return tids.map { readText("/proc/self/task/$it/comm") }
}

private fun readText(path: String): String {
    val file = fopen(path, "r") ?: return ""
    return try {
        memScoped {
            val buffer = allocArray<ByteVar>(LINE)
            buildString {
                while (true) append(fgets(buffer, LINE, file)?.toKString() ?: break)
            }
        }
    } finally {
        fclose(file)
    }
}

private const val LINE = 256
