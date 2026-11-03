// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.server.app

import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getpid
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.system
import kotlin.test.Test

/**
 * THROWAWAY diagnostic (never merged): test applications in a row in one native process, with every HttpClient of
 * Koin resolved and a burst of calls each; the process's threads after each, and gdb's backtrace of every thread,
 * grouped by their first Kotlin frames, after the 1st and the last application.
 */
class AppsInARowDiagTest {
    @Test
    fun appsInARow() {
        println("DIAG threads before: ${threads().size}")
        repeat(APPS) { app ->
            serverTest(serverModules()) { server ->
                val clients = runCatching { server.koin.getAll<HttpClient>().size }.getOrElse { -1 }
                coroutineScope {
                    List(BURST) { async { server.client.get(if (it % 2 == 0) "/health" else "/ready").status.value } }
                        .awaitAll()
                }
                println("DIAG app $app: koin http clients $clients")
            }
            val names = threads()
            println("DIAG threads after app $app: ${names.size} " +
                names.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6))
            if (app == 0 || app == APPS - 1) stacks("after app $app")
        }
    }

    private fun stacks(label: String) {
        val file = "/tmp/ostrom-bt.txt"
        val status = system("sudo -n gdb -p ${getpid()} -batch -ex 'thread apply all bt 40' > $file 2>&1")
        val text = readFile(file).orEmpty()
        println("DIAG gdb $label: status $status, ${text.length} chars")
        val groups = text.split(Regex("\nThread ")).drop(1).map { thread ->
            thread.lines().filter { it.contains("kfun:") || it.contains(" in ") }
                .map { it.substringAfter(" in ").substringBefore(" (").take(110) }
                .filterNot { it.startsWith("??") }
                .take(6).joinToString(" < ")
        }
        groups.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .forEach { (stack, count) -> println("DIAG x$count $stack") }
    }

    private fun threads(): List<String> {
        val dir = opendir("/proc/self/task") ?: return emptyList()
        val tids = mutableListOf<String>()
        try {
            var entry = readdir(dir)
            while (entry != null) {
                tids += entry.pointed.d_name.toKString()
                entry = readdir(dir)
            }
        } finally {
            closedir(dir)
        }
        return tids.filterNot { it.startsWith(".") }.map { readFile("/proc/self/task/$it/comm")?.trim().orEmpty() }
    }

    private fun readFile(path: String): String? {
        val file = fopen(path, "r") ?: return null
        try {
            return memScoped {
                val buffer = allocArray<ByteVar>(BUFFER)
                buildString { while (fgets(buffer, BUFFER, file) != null) append(buffer.toKString()) }
            }
        } finally {
            fclose(file)
        }
    }

    private companion object {
        const val APPS = 8
        const val BURST = 200
        const val BUFFER = 4096
    }
}
