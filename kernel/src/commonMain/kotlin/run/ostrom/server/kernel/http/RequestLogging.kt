// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http

import run.ostrom.core.telemetry.trace.SpanContext
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.observability.TelemetryLogger
import run.ostrom.server.kernel.observability.httpRoute
import run.ostrom.server.kernel.observability.spanContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val requestStart = AttributeKey<TimeSource.Monotonic.ValueTimeMark>("ostrom.request.start")
private val requestLogged = AttributeKey<Unit>("ostrom.request.logged")

/**
 * One structured line per answered request: method, route template ([httpRoute]: never the concrete path, which can
 * hold an artifact ticket or a public link's slug, nor the query string, which may carry a token; S2-45), status,
 * duration, request id, and the trace and span ids when the request is traced. The probes (`/health`, `/ready`) are
 * logged at DEBUG so as not to flood the logs. One line per call: a status page (the JSON of a 404) is sent from within
 * the first response, and both sends end in `ResponseSent`.
 */
val RequestLogging = createApplicationPlugin("RequestLogging", ::RequestLoggingConfig) {
    val logger = pluginConfig.logger
    onCall { call -> call.attributes.put(requestStart, TimeSource.Monotonic.markNow()) }
    on(ResponseSent) { call ->
        if (call.attributes.contains(requestLogged)) return@on
        call.attributes.put(requestLogged, Unit)
        logger.log(
            if (call.request.path() in PROBE_PATHS) LogLevel.DEBUG else LogLevel.INFO,
            "request served",
            requestFields(
                method = call.request.httpMethod.value,
                route = call.httpRoute,
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
 * @param route The route template, never the concrete path.
 * @param status The answered status; null only if the engine sent nothing.
 * @param start When the call started; null if the call skipped the plugin's start hook.
 * @param span The request's span; null when the request is not traced (`/health`, telemetry off).
 */
@Suppress("LongParameterList") // One parameter per field of the line, each read from a different part of the call.
internal fun requestFields(
    method: String,
    route: String,
    status: HttpStatusCode?,
    start: TimeMark?,
    requestId: String?,
    span: SpanContext?,
): Map<String, Any?> = mapOf(
    "method" to method,
    "route" to route,
    "status" to status?.value,
    "duration_ms" to start?.elapsedNow()?.inWholeMilliseconds,
    "request_id" to requestId,
    TelemetryLogger.TRACE_ID_FIELD to span?.traceIdHex,
    TelemetryLogger.SPAN_ID_FIELD to span?.spanIdHex,
    TelemetryLogger.TRACE_FLAGS_FIELD to span?.let(TelemetryLogger::traceFlags),
)
