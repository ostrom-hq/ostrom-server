package dev.ostrom.server.kernel.http

import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
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
 * status, duration and request id. `/health` is logged at DEBUG so probes do not flood the logs.
 */
val RequestLogging = createApplicationPlugin("RequestLogging", ::RequestLoggingConfig) {
    val logger = pluginConfig.logger
    onCall { call -> call.attributes.put(requestStart, TimeSource.Monotonic.markNow()) }
    on(ResponseSent) { call ->
        val path = call.request.path()
        logger.log(
            if (path == "/health") LogLevel.DEBUG else LogLevel.INFO,
            "request served",
            mapOf(
                "method" to call.request.httpMethod.value,
                "path" to path,
                "status" to call.response.status()?.value,
                "duration_ms" to call.attributes.getOrNull(requestStart)?.elapsedNow()?.inWholeMilliseconds,
                "request_id" to call.callId,
            ),
        )
    }
}
