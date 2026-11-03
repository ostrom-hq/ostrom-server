// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.server.app

import dev.ostrom.server.billing.domain.StripeGateway
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
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
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stopped application gives back every thread its HTTP clients held (P1, JOURNAL 03/11 23:38).
 *
 * Each HttpClient of Ktor's CIO engine runs a selector loop that holds one thread of Dispatchers.IO in `pselect` until
 * the client is closed, and Kotlin/Native runs at most 64 tasks of Dispatchers.IO at once. Four clients of Koin were
 * never closed (gateway, MCP relay, sharing, Stripe): every test application left four loops behind (gdb, server#342:
 * 4 threads in `SelectorHelper.selectionLoop` after the first application, 32 after the eighth), and once 64 of them
 * held Dispatchers.IO, no application of the process answered any more (the 16th in server#330, earlier in #247).
 */
class StoppedApplicationsTest {
    @Test
    fun aStoppedApplicationLeavesNoSelectorLoopBehind() {
        val before = selecting() ?: return // No /proc: macOS.
        repeat(APPLICATIONS) { application ->
            serverTest(serverModules()) { server ->
                // Every client of the application, built as they are on first use.
                server.koin.getAll<HttpClient>()
                server.koin.get<StripeGateway>()
                assertEquals(HttpStatusCode.OK, server.client.get("/health").status)
            }
            // A closed selector leaves its loop at its next wake-up.
            var left = selecting()!!
            var waits = 0
            while (left > before && waits++ < SETTLE_TURNS) {
                usleep(SETTLE_MICROS)
                left = selecting()!!
            }
            assertTrue(
                left <= before,
                "threads in a select: $before before the first application, $left after application ${application + 1}",
            )
        }
    }

    @Test
    fun theTwentiethApplicationInARowStillAnswers() {
        if (selecting() == null) return // No /proc: macOS.
        repeat(IN_A_ROW) { application ->
            serverTest(serverModules()) { server ->
                server.koin.getAll<HttpClient>()
                server.koin.get<StripeGateway>()
                assertEquals(HttpStatusCode.OK, server.client.get("/health").status, "application ${application + 1}")
            }
        }
    }

    /** The threads of the process waiting in a select or a poll, what a selector loop does; null without /proc. */
    private fun selecting(): Int? {
        val dir = opendir("/proc/self/task") ?: return null
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
        return tids.filterNot { it.startsWith(".") }
            .map { readFile("/proc/self/task/$it/wchan").orEmpty() }
            .count { it == "poll_schedule_timeout" || "select" in it }
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
        const val APPLICATIONS = 3
        const val IN_A_ROW = 20
        const val SETTLE_TURNS = 50
        const val SETTLE_MICROS = 100_000u
        const val BUFFER = 4096
    }
}
