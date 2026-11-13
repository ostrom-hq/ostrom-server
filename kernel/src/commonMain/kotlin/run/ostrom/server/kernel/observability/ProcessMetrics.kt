// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.observability

import run.ostrom.core.telemetry.metrics.GaugeReading
import run.ostrom.core.telemetry.metrics.Meter

/**
 * The server's own CPU time and resident memory (OpenTelemetry `process.cpu.time`, `process.memory.usage`), read only
 * when the metric reader collects (once per export interval): what the API dashboard plots next to the traffic, so a
 * CPU budget overrun is seen without a profiler.
 *
 * Both are gauges, the only asynchronous instrument of ostrom-core's meter: `process.cpu.time` is the cumulative
 * total, which `rate()` reads like a counter (a restart reads as a reset, as for a counter); the dashboards and the
 * metric catalog of ostrom-infra say so. A cumulative asynchronous counter in core would be the exact OpenTelemetry
 * type; it changes nothing to the queries.
 */
object ProcessMetrics {
    /** Registers both gauges on [meter]. */
    fun register(meter: Meter) {
        meter.gauge("process.cpu.time", "s", "CPU time used by the server (user + system)") {
            listOf(GaugeReading(processCpuSeconds()))
        }
        meter.gauge("process.memory.usage", "By", "Resident memory of the server") {
            readings(processResidentBytes())
        }
    }

    /** No reading where the memory cannot be read (a system without `/proc`). */
    internal fun readings(bytes: Long?): List<GaugeReading> =
        if (bytes == null) emptyList() else listOf(GaugeReading(bytes.toDouble()))
}

/** User + system CPU seconds of this process. */
internal expect fun processCpuSeconds(): Double

/** Resident bytes of this process; null where the system does not say. */
internal expect fun processResidentBytes(): Long?
