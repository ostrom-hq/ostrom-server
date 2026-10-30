// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.contentLength
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** The largest request body a route reads unless it declares another limit with [bodyLimit]: 1 MiB. */
const val DEFAULT_BODY_LIMIT: Long = 1L shl 20

private val bodyLimitKey = AttributeKey<Long>("ostrom.body.limit")

/**
 * Caps every request body the server reads (SECURITY-AUDIT S2-46): [DEFAULT_BODY_LIMIT], or the limit the matched
 * route declares with [bodyLimit]. A declared `Content-Length` over the limit is refused before a byte is read; a body
 * without one (chunked) is read up to one byte past the limit, never further. Either way the route's `receive` fails
 * with a [PayloadTooLargeException], answered 413 `payload_too_large` by [configureErrors], ahead of the 415 of a body
 * that is not JSON. The limit applies when a route reads the body: a route that must check a credential first does so
 * before calling `receive`. Installed by [configureHttp].
 */
val BodyLimit = createApplicationPlugin("BodyLimit") {
    on(ReceiveBody) { call, body ->
        limitedBody(body, call.attributes.getOrNull(bodyLimitKey) ?: DEFAULT_BODY_LIMIT, call.request.contentLength())
    }
}

/**
 * The body a route is given: [body] itself unless it is the raw request channel, which is read up to [limit] bytes.
 *
 * @param body The body as the receive pipeline has it.
 * @param limit The most bytes accepted.
 * @param declaredLength The request's `Content-Length`, if any.
 *
 * @return The body, at most [limit] bytes.
 *
 * @throws PayloadTooLargeException When the declared or the actual length is over [limit].
 */
internal suspend fun limitedBody(body: Any, limit: Long, declaredLength: Long?): Any {
    if (body !is ByteReadChannel) return body
    if (declaredLength != null && declaredLength > limit) throw PayloadTooLargeException(limit)
    val bytes = body.readRemaining(limit + 1).readByteArray()
    if (bytes.size > limit) throw PayloadTooLargeException(limit)
    return ByteReadChannel(bytes)
}

/**
 * Routes whose bodies may be up to [bytes] long instead of [DEFAULT_BODY_LIMIT]: larger for a route that takes files,
 * smaller for one that takes a few fields. The innermost declaration wins.
 *
 * @param bytes The most bytes a body of these routes may hold.
 * @param build The routes.
 *
 * @return The route holding them.
 */
fun Route.bodyLimit(bytes: Long, build: Route.() -> Unit): Route {
    require(bytes > 0) { "A body limit is positive." }
    val limited = createChild(BodyLimitSelector(bytes))
    limited.install(RouteBodyLimit) { this.bytes = bytes }
    limited.build()
    return limited
}

/** Configuration of [RouteBodyLimit]. */
private class RouteBodyLimitConfig {
    var bytes: Long = DEFAULT_BODY_LIMIT
}

/** Records the limit declared by [bodyLimit] on the calls of its routes, for [BodyLimit]. */
private val RouteBodyLimit = createRouteScopedPlugin("RouteBodyLimit", ::RouteBodyLimitConfig) {
    val bytes = pluginConfig.bytes
    onCall { call -> call.attributes.put(bodyLimitKey, bytes) }
}

/** Matches like its parent; parenthesized so that route templates leave it out. */
private class BodyLimitSelector(private val bytes: Long) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(body limit $bytes)"
}

/** The receive pipeline's first phase, where the body is still the request's raw channel. */
private object ReceiveBody : Hook<suspend (ApplicationCall, Any) -> Any> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall, Any) -> Any) {
        pipeline.receivePipeline.intercept(ApplicationReceivePipeline.Before) { body ->
            proceedWith(handler(call, body))
        }
    }
}
