package dev.ostrom.server.kernel.observability

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.Telemetry
import dev.ostrom.core.telemetry.trace.Span
import dev.ostrom.core.telemetry.trace.SpanContext
import dev.ostrom.core.telemetry.trace.SpanContextElement
import dev.ostrom.core.telemetry.trace.SpanKind
import dev.ostrom.core.telemetry.trace.StatusCode
import dev.ostrom.core.telemetry.trace.setAttribute
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
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

private const val HEALTH_PATH = "/health"
private const val SERVER_ERROR = 500
private const val NOT_FOUND = 404

private val routeKey = AttributeKey<String>("ostrom.route")
private val spanKey = AttributeKey<SpanContext>("ostrom.span")

/** The span context of the request, for logs that want to link to their trace; null for `/health` and in tests. */
val ApplicationCall.spanContext: SpanContext? get() = attributes.getOrNull(spanKey)

/**
 * Traces and measures every request: a SERVER span continuing the caller's `traceparent` (the CLI sends one), named
 * `METHOD /route/{template}` once routing matched, carried in the coroutine context so that a module's own spans and
 * model calls nest under it; the trace is returned in `traceresponse`. `/health` is measured but never traced (probes
 * would fill the traces). The span records the route template, method and status, never the path, the query or a
 * header value. Telemetry never fails a request: exporting is a non-blocking queue (CONVENTIONS 16.4).
 *
 * @param telemetry Where spans go.
 * @param metrics Where request durations go.
 */
fun Application.installTelemetry(telemetry: Telemetry, metrics: OstromMetrics) {
    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        call.attributes.put(routeKey, routeTemplate(call.route))
    }
    intercept(ApplicationCallPipeline.Monitoring) {
        val started = TimeSource.Monotonic.markNow()
        val method = call.request.httpMethod.value
        metrics.requestStarted()
        val span = if (call.request.path() == HEALTH_PATH) {
            null
        } else {
            val parent = SpanContext.parseTraceparent(call.request.headers[SpanContext.TRACEPARENT_HEADER])
            telemetry.tracer.startSpan(method, SpanKind.SERVER, parent, Attributes.of("http.request.method" to method))
        }
        span?.let {
            call.attributes.put(spanKey, it.context)
            call.response.headers.append(TRACE_RESPONSE_HEADER, it.context.toTraceparent())
        }
        var failed = false
        try {
            if (span == null) proceed() else withContext(SpanContextElement(span.context)) { proceed() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failed = true
            throw error
        } finally {
            val route = call.attributes.getOrNull(routeKey) ?: UNMATCHED_ROUTE
            // No status yet: the engine answers 404 after the pipeline when nothing responded (no route matched).
            val status = call.response.status()?.value ?: if (failed) SERVER_ERROR else NOT_FOUND
            metrics.requestServed(method, route, status, started.elapsedNow())
            span?.finish(method, route, status)
        }
    }
}

private fun Span.finish(method: String, route: String, status: Int) {
    updateName("$method $route")
    setAttribute("http.route", route)
    setAttribute("http.response.status_code", status.toLong())
    if (status >= SERVER_ERROR) setStatus(StatusCode.ERROR)
    end()
}

/**
 * The template of a matched route, without Ktor's selector segments: `/(authenticate token)/v1/gates/{id}/(method:GET)`
 * becomes `/v1/gates/{id}`.
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
internal fun routeTemplate(path: String): String =
    "/" + path.split('/').filter { it.isNotEmpty() && !(it.startsWith('(') && it.endsWith(')')) }.joinToString("/")
