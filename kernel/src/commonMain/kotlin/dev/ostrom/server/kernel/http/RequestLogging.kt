// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

import dev.ostrom.server.kernel.logging.LogLevel
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

/**
 * One structured line per answered request: method, route template ([routeTemplate]: never the concrete path, which
 * can hold an artifact ticket or a public link's slug, nor the query string, which may carry a token; S2-45), status,
 * duration and request id. The health route is logged at DEBUG so probes do not flood the logs.
 */
val RequestLogging = createApplicationPlugin("RequestLogging", ::RequestLoggingConfig) {
    val logger = pluginConfig.logger
    onCall { call -> call.attributes.put(requestStart, TimeSource.Monotonic.markNow()) }
    on(ResponseSent) { call ->
        logger.log(
            if (call.request.path() in HEALTH_PATHS) LogLevel.DEBUG else LogLevel.INFO,
            "request served",
            requestFields(
                method = call.request.httpMethod.value,
                route = call.routeTemplate,
                status = call.response.status(),
                start = call.attributes.getOrNull(requestStart),
                requestId = call.callId,
            ),
        )
    }
}

/**
 * The fields of a request line.
 *
 * @param route The template of the matched route, never the path.
 * @param status The answered status; null only if the engine sent nothing.
 * @param start When the call started; null if the call skipped the plugin's start hook.
 */
internal fun requestFields(
    method: String,
    route: String,
    status: HttpStatusCode?,
    start: TimeMark?,
    requestId: String?,
): Map<String, Any?> = mapOf(
    "method" to method,
    "route" to route,
    "status" to status?.value,
    "duration_ms" to start?.elapsedNow()?.inWholeMilliseconds,
    "request_id" to requestId,
)
