// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.observability

import run.ostrom.core.telemetry.AttributeValue
import run.ostrom.core.telemetry.logs.Severity
import run.ostrom.core.telemetry.trace.SpanContext
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class TelemetryLoggerTest {
    private val telemetry = RecordingTelemetry()
    private val lines = mutableListOf<String>()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_800_000_000)
    }
    private val logger =
        TelemetryLogger(JsonLogger(LogLevel.INFO, clock, { lines += it }), telemetry, LogLevel.INFO, clock)

    @Test
    fun anEventIsWrittenAndExportedWithTypedFieldsAndItsTrace() {
        logger.with(mapOf("module" to "gates")).warn(
            "gate expired",
            mapOf(
                "count" to 3,
                "elapsed_ms" to 12L,
                "ratio" to 0.5,
                "first" to true,
                "kind" to GateKindProbe.MERGE,
                "missing" to null,
                TelemetryLogger.TRACE_ID_FIELD to "0af7651916cd43dd8448eb211c80319c",
                TelemetryLogger.SPAN_ID_FIELD to "b7ad6b7169203331",
            ),
            IllegalStateException("password=hunter2"),
        )

        assertTrue(lines.single().contains("\"msg\":\"gate expired\""))
        val record = telemetry.logs.single()
        assertEquals(1_800_000_000_000_000_000L, record.epochNanos)
        assertEquals(Severity.WARN, record.severity)
        assertEquals("gate expired", record.body)
        assertEquals(AttributeValue.Text("gates"), record.attributes["module"])
        assertEquals(AttributeValue.Integer(3), record.attributes["count"])
        assertEquals(AttributeValue.Integer(12), record.attributes["elapsed_ms"])
        assertEquals(AttributeValue.Decimal(0.5), record.attributes["ratio"])
        assertEquals(AttributeValue.Flag(true), record.attributes["first"])
        assertEquals(AttributeValue.Text("MERGE"), record.attributes["kind"])
        assertNull(record.attributes["missing"])
        assertEquals(AttributeValue.Text("IllegalStateException"), record.attributes["exception.type"])
        assertTrue(record.attributes.entries.none { it.value.toString().contains("hunter2") })
        assertEquals("0af7651916cd43dd8448eb211c80319c", record.spanContext?.traceIdHex)
        assertEquals("b7ad6b7169203331", record.spanContext?.spanIdHex)
        assertEquals(true, record.spanContext?.sampled)
    }

    @Test
    fun anUnsampledTraceStaysUnsampledInItsLogs() {
        val span = SpanContext(1L, 2L, 3L, sampled = false)
        logger.info(
            "a",
            mapOf(
                TelemetryLogger.TRACE_ID_FIELD to span.traceIdHex,
                TelemetryLogger.SPAN_ID_FIELD to span.spanIdHex,
                TelemetryLogger.TRACE_FLAGS_FIELD to TelemetryLogger.traceFlags(span),
            ),
        )

        assertEquals(span, telemetry.logs.single().spanContext)
        assertEquals("01", TelemetryLogger.traceFlags(span.copy(sampled = true)))
    }

    @Test
    fun aSpanIdAloneLinksToNoTrace() {
        logger.info("a", mapOf(TelemetryLogger.SPAN_ID_FIELD to "b7ad6b7169203331"))

        assertNull(telemetry.logs.single().spanContext)
    }

    @Test
    fun everyLevelMapsToItsSeverityAndAnEventWithoutIdsHasNoTrace() {
        logger.info("a", mapOf(TelemetryLogger.TRACE_ID_FIELD to "0af7651916cd43dd8448eb211c80319c"))
        logger.error("b")
        TelemetryLogger(JsonLogger(LogLevel.DEBUG, sink = {}), telemetry, LogLevel.DEBUG).debug("c")

        assertEquals(listOf(Severity.INFO, Severity.ERROR, Severity.DEBUG), telemetry.logs.map { it.severity })
        assertTrue(telemetry.logs.all { it.spanContext == null })
    }

    @Test
    fun anEventBelowTheLevelIsNeitherWrittenNorExported() {
        logger.debug("noise")

        assertTrue(lines.isEmpty())
        assertTrue(telemetry.logs.isEmpty())
    }

    @Test
    fun anErrorWithoutASimpleNameIsAThrowable() {
        val anonymous = object : RuntimeException() {}
        logger.error("anonymous", error = anonymous)

        // Anonymous classes have no simple name on the JVM; Kotlin/Native may name them.
        val expected = anonymous::class.simpleName ?: "Throwable"
        assertEquals(AttributeValue.Text(expected), telemetry.logs.single().attributes["exception.type"])
    }

    private enum class GateKindProbe { MERGE }
}
