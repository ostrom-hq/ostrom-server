package dev.ostrom.server.kernel.observability

import kotlin.test.Test
import kotlin.test.assertTrue

class ProcessMetricsTest {
    @Test
    fun cpuTimeGrowsWithWork() {
        val before = ProcessMetrics.cpuSeconds()
        var spin = 0L
        while (ProcessMetrics.cpuSeconds() - before < 0.01) spin++

        assertTrue(spin > 0 && ProcessMetrics.cpuSeconds() >= before)
    }

    @Test
    fun residentMemoryIsReadWhereProcIsMounted() {
        val bytes = ProcessMetrics.residentBytes()
        // Linux (CI, containers): a few megabytes at least; macOS has no /proc and says null.
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
}
