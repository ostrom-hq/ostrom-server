// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.server.app

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

internal actual fun processStatus(): String {
    val file = fopen("/proc/self/status", "r") ?: return ""
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
