// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.observability

import run.ostrom.core.telemetry.Attributes
import run.ostrom.core.telemetry.Telemetry
import run.ostrom.core.telemetry.analytics.ProductEvent
import run.ostrom.core.telemetry.logs.LogRecord
import run.ostrom.core.telemetry.metrics.HistogramPoint
import run.ostrom.core.telemetry.metrics.MeterImpl
import run.ostrom.core.telemetry.metrics.MetricData
import run.ostrom.core.telemetry.metrics.NumberPoint
import run.ostrom.core.telemetry.trace.RandomIdGenerator
import run.ostrom.core.telemetry.trace.Sampler
import run.ostrom.core.telemetry.trace.SpanData
import run.ostrom.core.telemetry.trace.Tracer
import run.ostrom.core.telemetry.trace.TracerImpl
import kotlin.time.Clock
import kotlin.time.Duration

/** Telemetry that keeps everything in memory, for assertions. */
class RecordingTelemetry : Telemetry {
    val spans = mutableListOf<SpanData>()
    val logs = mutableListOf<LogRecord>()
    val events = mutableListOf<ProductEvent>()
    private val meterImpl = MeterImpl(Clock.System)

    override val tracer: Tracer = TracerImpl(Clock.System, RandomIdGenerator(), Sampler.parentBasedRatio(1.0), {
        spans += it
    })
    override val meter: MeterImpl = meterImpl

    override fun log(record: LogRecord) {
        logs += record
    }

    override fun capture(event: ProductEvent) {
        events += event
    }

    override suspend fun shutdown(timeout: Duration) = Unit

    fun metric(name: String): MetricData = meterImpl.collect().single { it.name == name }

    fun sum(name: String, attributes: Attributes): Double =
        (metric(name) as MetricData.Sum).points.single { it.attributes == attributes }.value

    fun gauge(name: String): List<NumberPoint> = (metric(name) as MetricData.Gauge).points

    fun histogram(name: String, attributes: Attributes): HistogramPoint =
        (metric(name) as MetricData.Distribution).points.single { it.attributes == attributes }
}
