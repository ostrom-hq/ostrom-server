package dev.ostrom.server.kernel.observability

import dev.ostrom.core.telemetry.metrics.GaugeReading
import dev.ostrom.core.telemetry.metrics.Meter
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.RUSAGE_SELF
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getrusage
import platform.posix.rusage
import platform.posix.sysconf
import platform.posix._SC_PAGESIZE

/**
 * The server's own CPU time and resident memory (OpenTelemetry `process.cpu.time`, `process.memory.usage`), read only
 * when the metric reader collects (once per export interval): what the API dashboard plots next to the traffic, so a
 * CPU budget overrun is seen without a profiler.
 */
@OptIn(ExperimentalForeignApi::class)
object ProcessMetrics {
    /** Registers both gauges on [meter]. */
    fun register(meter: Meter) {
        meter.gauge("process.cpu.time", "s", "CPU time used by the server (user + system)") {
            listOf(GaugeReading(cpuSeconds()))
        }
        meter.gauge("process.memory.usage", "By", "Resident memory of the server") {
            residentBytes()?.let { listOf(GaugeReading(it.toDouble())) } ?: emptyList()
        }
    }

    /** User + system CPU seconds of this process. */
    fun cpuSeconds(): Double = memScoped {
        val usage = alloc<rusage>()
        getrusage(RUSAGE_SELF, usage.ptr)
        val user = usage.ru_utime.tv_sec.toDouble() + usage.ru_utime.tv_usec.toDouble() / MICROS
        val system = usage.ru_stime.tv_sec.toDouble() + usage.ru_stime.tv_usec.toDouble() / MICROS
        user + system
    }

    /** Resident bytes from `/proc/self/statm` (Linux, where the server runs); null elsewhere. */
    fun residentBytes(): Long? {
        val file = fopen("/proc/self/statm", "r") ?: return null
        val line = try {
            memScoped {
                val buffer = allocArray<kotlinx.cinterop.ByteVar>(LINE)
                fgets(buffer, LINE, file)?.toKString()
            }
        } finally {
            fclose(file)
        }
        val pages = line?.trim()?.split(' ')?.getOrNull(1)?.toLongOrNull() ?: return null
        return pages * sysconf(_SC_PAGESIZE)
    }

    private const val MICROS = 1_000_000.0
    private const val LINE = 128
}
