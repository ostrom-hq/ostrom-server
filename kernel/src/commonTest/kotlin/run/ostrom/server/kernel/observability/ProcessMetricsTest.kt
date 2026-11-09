// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessMetricsTest {
    @Test
    fun cpuTimeGrowsWithWork() {
        // The clock ticks by 10 ms on Linux: on a loaded runner it could already have moved past 0.01 s before a
        // `while` tested it once, leaving no spin (run 41514183276). The work loop runs at least once, and the test
        // asserts what it waited for, never how many turns that took.
        val before = processCpuSeconds()
        var spin = 0L
        var after: Double
        do {
            spin++
            after = processCpuSeconds()
        } while (after - before < 0.01)

        assertTrue(spin >= 1 && after - before >= 0.01, "spin $spin, ${after - before} s")
    }

    @Test
    fun residentMemoryIsReadWhereTheSystemSaysIt() {
        val bytes = processResidentBytes()
        // Linux (CI, containers) and the JVM: a megabyte at least; macOS has no /proc and says null.
        assertTrue(bytes == null || bytes > 1_000_000, "$bytes")
    }

    @Test
    fun bothGaugesAreExportedOnCollection() {
        val telemetry = RecordingTelemetry()
        ProcessMetrics.register(telemetry.meter)

        assertTrue(telemetry.gauge("process.cpu.time").single().value >= 0.0)
        val memory = telemetry.meter.collect().filter { it.name == "process.memory.usage" }
        assertTrue(memory.size <= 1)
    }

    @Test
    fun anUnreadableMemoryGivesNoReading() {
        assertEquals(emptyList(), ProcessMetrics.readings(null))
        assertEquals(listOf(2048.0), ProcessMetrics.readings(2048).map { it.value })
    }
}
