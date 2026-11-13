// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package run.ostrom.server.app

import run.ostrom.server.billing.domain.StripeGateway
import run.ostrom.server.testing.serverTest
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
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.Platform
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
        // Settled first: the loops of a test stopped just before in this process may still be leaving.
        val before = settled() ?: return // No /proc (macOS), or an architecture without its pselect6 below.
        repeat(APPLICATIONS) { application ->
            serverTest(serverModules()) { server ->
                // Every client of the application, built as they are on first use.
                server.koin.getAll<HttpClient>()
                server.koin.get<StripeGateway>()
                assertEquals(HttpStatusCode.OK, server.client.get("/health").status)
                // The control: a count that cannot see the running loops would pass on any leak.
                val running = selecting()!!
                assertTrue(running > before, "$running threads in a select while application ${application + 1} runs")
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

    /**
     * The threads of the process in `pselect6`, where a selector loop waits (gdb: `pselect64_syscall` under
     * `SelectorHelper.selectionLoop`); null without /proc. Read from `syscall`, whose first field is the number of the
     * call in progress: the wait channel's name depends on the kernel (the first version of this test, matching it,
     * passed on the leak, #344).
     */
    private fun selecting(): Int? {
        val pselect6 = PSELECT6[Platform.cpuArchitecture]
        val tids = if (pselect6 == null) null else taskIds()
        return tids?.filterNot { it.startsWith(".") }
            ?.count { readFile("/proc/self/task/$it/syscall")?.substringBefore(' ') == pselect6 }
    }

    private fun taskIds(): List<String>? {
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
        return tids
    }

    /** [selecting] once two reads a tenth of a second apart agree. */
    private fun settled(): Int? {
        var count = selecting()
        var turns = 0
        while (count != null && turns < SETTLE_TURNS) {
            usleep(SETTLE_MICROS)
            val next = selecting()
            turns = if (next == count) SETTLE_TURNS else turns + 1
            count = next
        }
        return count
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

        /** The number of `pselect6` per architecture, as `syscall` writes it. */
        val PSELECT6 = mapOf(CpuArchitecture.X64 to "270", CpuArchitecture.ARM64 to "72")
    }
}
