// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app.isolation

import java.io.File

/** The JVM reads the same files as Kotlin/Native, on Linux. */
internal actual fun processThreadNames(): List<String>? =
    File("/proc/self/task").listFiles()?.map { File(it, "comm").readText().trim() }
