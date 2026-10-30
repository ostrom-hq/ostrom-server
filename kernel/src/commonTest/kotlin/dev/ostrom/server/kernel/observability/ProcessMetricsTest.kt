// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessMetricsTest {
    @Test
    fun cpuTimeGrowsWithWork() {
        val before = processCpuSeconds()
        var spin = 0L
        while (processCpuSeconds() - before < 0.01) spin++

        assertTrue(spin > 0 && processCpuSeconds() >= before)
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
