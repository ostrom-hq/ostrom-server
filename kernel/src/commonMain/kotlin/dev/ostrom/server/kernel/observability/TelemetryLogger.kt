package dev.ostrom.server.kernel.observability

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.Telemetry
import dev.ostrom.core.telemetry.logs.LogRecord
import dev.ostrom.core.telemetry.logs.Severity
import dev.ostrom.core.telemetry.trace.SpanContext
import dev.ostrom.core.telemetry.trace.epochNanos
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import kotlin.time.Clock

/**
 * Writes every event to [delegate] (the JSON lines on standard output) and, when an OTLP destination is configured,
 * exports it as an OpenTelemetry log record linked to its trace through the `trace_id` and `span_id` fields. The
 * error's type is exported, never its message (it may quote a provider or a secret).
 *
 * @param delegate The logger that writes the line.
 * @param telemetry Where records go.
 * @param minLevel The lowest level exported, the same as the delegate's.
 * @param clock Stamps the records.
 * @param context Fields added to every event by [with], already known to [delegate].
 */
class TelemetryLogger(
    private val delegate: Logger,
    private val telemetry: Telemetry,
    private val minLevel: LogLevel,
    private val clock: Clock = Clock.System,
    private val context: Map<String, Any?> = emptyMap(),
) : Logger {
    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        delegate.log(level, message, fields, error)
        if (level < minLevel) return
        val all = context + fields
        val attributes = Attributes.Builder()
        all.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Int -> attributes.put(key, value)
                is Long -> attributes.put(key, value)
                is Double -> attributes.put(key, value)
                is Boolean -> attributes.put(key, value)
                else -> attributes.put(key, value.toString())
            }
        }
        error?.let { attributes.put("exception.type", it::class.simpleName ?: "Throwable") }
        telemetry.log(LogRecord(clock.epochNanos(), level.severity(), message, attributes.build(), all.spanContext()))
    }

    override fun with(fields: Map<String, Any?>): Logger =
        TelemetryLogger(delegate.with(fields), telemetry, minLevel, clock, context + fields)

    private fun LogLevel.severity(): Severity = when (this) {
        LogLevel.DEBUG -> Severity.DEBUG
        LogLevel.INFO -> Severity.INFO
        LogLevel.WARN -> Severity.WARN
        LogLevel.ERROR -> Severity.ERROR
    }

    private fun Map<String, Any?>.spanContext(): SpanContext? {
        val traceId = this[TRACE_ID_FIELD] ?: return null
        val spanId = this[SPAN_ID_FIELD] ?: return null
        return SpanContext.parseTraceparent("00-$traceId-$spanId-01")
    }

    /** The field names that link a log event to its trace. */
    companion object {
        /** The trace id, 32 lower-case hex digits. */
        const val TRACE_ID_FIELD: String = "trace_id"

        /** The span id, 16 lower-case hex digits. */
        const val SPAN_ID_FIELD: String = "span_id"
    }
}
