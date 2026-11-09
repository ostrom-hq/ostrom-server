// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.observability

import run.ostrom.core.telemetry.Attributes
import run.ostrom.core.telemetry.Telemetry
import run.ostrom.core.telemetry.trace.Span
import run.ostrom.core.telemetry.trace.SpanContext
import run.ostrom.core.telemetry.trace.SpanContextElement
import run.ostrom.core.telemetry.trace.SpanKind
import run.ostrom.core.telemetry.trace.StatusCode
import run.ostrom.core.telemetry.trace.setAttribute
import run.ostrom.server.kernel.http.PROBE_PATHS
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** The response header that gives the caller the trace of its request (W3C Trace Context level 2). */
const val TRACE_RESPONSE_HEADER: String = "traceresponse"

/** The route template of an unmatched request (404 before routing): never the concrete path. */
const val UNMATCHED_ROUTE: String = "unmatched"

private const val SERVER_ERROR = 500
private const val NOT_FOUND = 404
private const val CLIENT_CLOSED_REQUEST = 499
private const val OTHER_METHOD = "_OTHER"
private val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH")

private val routeKey = AttributeKey<String>("ostrom.route")
private val routeTemplatesRecorded = AttributeKey<Unit>("ostrom.route.recorded")
private val spanKey = AttributeKey<SpanContext>("ostrom.span")

/** The span context of the request, for logs that want to link to their trace; null for the probes and in tests. */
val ApplicationCall.spanContext: SpanContext? get() = attributes.getOrNull(spanKey)

/**
 * The route template of the request (`/a/{id}/{ticket}/{...}`), [UNMATCHED_ROUTE] when no route matched: what logs,
 * spans and metrics carry, never the concrete path, which may hold an artifact ticket or a share slug.
 */
val ApplicationCall.httpRoute: String get() = attributes.getOrNull(routeKey) ?: UNMATCHED_ROUTE

/**
 * Records the route template of every routed call, for [httpRoute]. Installed once, by whichever of the request logs
 * and the telemetry asks first, so that the logs have it with telemetry off.
 */
fun Application.recordRouteTemplates() {
    if (attributes.contains(routeTemplatesRecorded)) return
    attributes.put(routeTemplatesRecorded, Unit)
    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        call.attributes.put(routeKey, routeTemplate(call.route))
    }
}

/**
 * Traces and measures every request: a SERVER span continuing the caller's `traceparent` (the CLI sends one), named
 * `METHOD /route/{template}` once routing matched, carried in the coroutine context so that a module's own spans and
 * model calls nest under it; the trace is returned in `traceresponse`. The probes (`/health`, `/ready`) are measured
 * but never traced (they would fill the traces). The span records the route template, method and status, never the
 * path, the query or a header value: a path may carry an artifact ticket or a share slug. Telemetry never fails a
 * request: exporting is a non-blocking queue (CONVENTIONS 16.4).
 *
 * @param telemetry Where spans go.
 * @param metrics Where request durations go.
 */
fun Application.installTelemetry(telemetry: Telemetry, metrics: OstromMetrics) {
    recordRouteTemplates()
    intercept(ApplicationCallPipeline.Monitoring) {
        val started = TimeSource.Monotonic.markNow()
        val method = knownMethod(call.request.httpMethod.value)
        var span: Span? = null
        var ending = Ending.ANSWERED
        metrics.requestStarted()
        // Everything after the in-flight increment is inside the try: the finally always decrements it.
        try {
            span = if (call.request.path() in PROBE_PATHS) null else startSpan(call, telemetry, method)
            if (span == null) proceed() else withContext(SpanContextElement(span.context)) { proceed() }
        } catch (cancelled: CancellationException) {
            ending = Ending.CANCELLED
            throw cancelled
        } catch (error: Throwable) {
            ending = Ending.FAILED
            throw error
        } finally {
            call.endRequest(metrics, span, method, ending, started)
        }
    }
}

/**
 * Records the end of a request: its duration and the in-flight decrement, then the end of its span, even if measuring
 * failed. One function rather than the finally's own body, so that each exit path runs the same code.
 */
private fun ApplicationCall.endRequest(
    metrics: OstromMetrics,
    span: Span?,
    method: String,
    ending: Ending,
    started: TimeSource.Monotonic.ValueTimeMark,
) {
    val route = httpRoute
    val status = response.status()?.value ?: ending.status
    try {
        metrics.requestServed(method, route, status, started.elapsedNow())
    } finally {
        span?.finish(method, route, status)
    }
}

/** How a request ended when no status was written: the status recorded for it. */
private enum class Ending(val status: Int) {
    /** The engine answers 404 after the pipeline when nothing responded (no route matched). */
    ANSWERED(NOT_FOUND),

    /** A handler threw: the engine answers 500. */
    FAILED(SERVER_ERROR),

    /** The client went away or the call was cancelled: 499, as proxies record it, never a 404 nor an error. */
    CANCELLED(CLIENT_CLOSED_REQUEST),
}

private fun startSpan(call: ApplicationCall, telemetry: Telemetry, method: String): Span {
    val parent = SpanContext.parseTraceparent(call.request.headers[SpanContext.TRACEPARENT_HEADER])
    val attributes = Attributes.of("http.request.method" to method)
    val span = telemetry.tracer.startSpan(method, SpanKind.SERVER, parent, attributes)
    call.attributes.put(spanKey, span.context)
    call.response.headers.append(TRACE_RESPONSE_HEADER, span.context.toTraceparent())
    return span
}

/**
 * The method as metrics and span names record it: one of the HTTP methods, else `_OTHER` (OpenTelemetry semantic
 * conventions), so that a client cannot create series or span names at will.
 */
internal fun knownMethod(method: String): String = if (method in HTTP_METHODS) method else OTHER_METHOD

private fun Span.finish(method: String, route: String, status: Int) {
    updateName("$method $route")
    setAttribute("http.route", route)
    setAttribute("http.response.status_code", status.toLong())
    if (status >= SERVER_ERROR) setStatus(StatusCode.ERROR)
    end()
}

/**
 * The template of a matched route, without Ktor's selector segments, parenthesized or bracketed (query parameters):
 * `/(authenticate token)/v1/gates/{id}/[limit?]/(method:GET)` becomes `/v1/gates/{id}`. Ktor names a tail segment
 * `{...}`.
 *
 * @param node The matched routing node.
 *
 * @return The template, `/` for the root.
 */
internal fun routeTemplate(node: RoutingNode): String = routeTemplate(node.toString())

/**
 * @param path Ktor's description of a routing node.
 *
 * @return The template without selector segments, `/` for the root.
 */
internal fun routeTemplate(path: String): String = "/" + path.split('/').filter { segment ->
    segment.isNotEmpty() && !segment.isSelector('(', ')') && !segment.isSelector('[', ']')
}.joinToString("/")

private fun String.isSelector(open: Char, close: Char): Boolean = startsWith(open) && endsWith(close)
