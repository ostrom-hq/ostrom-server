// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import java.io.File

internal actual fun processStatus(): String = File("/proc/self/status").takeIf { it.exists() }?.readText().orEmpty()

internal actual fun threadNames(): List<String> = File("/proc/self/task").listFiles().orEmpty()
    .mapNotNull { File(it, "comm").takeIf { comm -> comm.exists() }?.readText() }

internal actual fun kotlinHeapKb(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / KB

internal actual fun afterCollection(): String = ""

private const val KB = 1024
