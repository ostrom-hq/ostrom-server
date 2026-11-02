// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import java.io.File

internal actual fun processStatus(): String = File("/proc/self/status").takeIf { it.exists() }?.readText().orEmpty()

internal actual fun threadNames(): List<String> = File("/proc/self/task").listFiles().orEmpty()
    .mapNotNull { File(it, "comm").takeIf { comm -> comm.exists() }?.readText() }
