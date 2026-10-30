package dev.ostrom.server.kernel.http

import dev.ostrom.core.telemetry.trace.SpanContext
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.observability.TelemetryLogger
import dev.ostrom.server.kernel.observability.spanContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Configuration of [RequestLogging].
 *
 * @property logger Where request lines go; set by [configureHttp].
 */
class RequestLoggingConfig {
    lateinit var logger: Logger
}

private val requestStart = AttributeKey<TimeSource.Monotonic.ValueTimeMark>("ostrom.request.start")

/**
 * One structured line per answered request: method, path (never the query string, which may carry a token),
 * status, duration, request id, and the trace and span ids when the request is traced. `/health` is logged at DEBUG
 * so probes do not flood the logs.
 */
val RequestLogging = createApplicationPlugin("RequestLogging", ::RequestLoggingConfig) {
    val logger = pluginConfig.logger
    onCall { call -> call.attributes.put(requestStart, TimeSource.Monotonic.markNow()) }
    on(ResponseSent) { call ->
        val path = call.request.path()
        logger.log(
            if (path == "/health") LogLevel.DEBUG else LogLevel.INFO,
            "request served",
            requestFields(
                method = call.request.httpMethod.value,
                path = path,
                status = call.response.status(),
                start = call.attributes.getOrNull(requestStart),
                requestId = call.callId,
                span = call.spanContext,
            ),
        )
    }
}

/**
 * The fields of a request line.
 *
 * @param status The answered status; null only if the engine sent nothing.
 * @param start When the call started; null if the call skipped the plugin's start hook.
 * @param span The request's span; null when the request is not traced (`/health`, telemetry off).
 */
internal fun requestFields(
    method: String,
    path: String,
    status: HttpStatusCode?,
    start: TimeMark?,
    requestId: String?,
    span: SpanContext?,
): Map<String, Any?> = mapOf(
    "method" to method,
    "path" to path,
    "status" to status?.value,
    "duration_ms" to start?.elapsedNow()?.inWholeMilliseconds,
    "request_id" to requestId,
    TelemetryLogger.TRACE_ID_FIELD to span?.traceIdHex,
    TelemetryLogger.SPAN_ID_FIELD to span?.spanIdHex,
)
