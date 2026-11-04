// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.observability

import dev.ostrom.core.telemetry.AttributeValue
import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.Telemetry
import dev.ostrom.core.telemetry.metrics.Histogram
import dev.ostrom.core.telemetry.metrics.Meter
import dev.ostrom.core.telemetry.metrics.NoopMeter
import dev.ostrom.core.telemetry.trace.SpanContext
import dev.ostrom.core.telemetry.trace.SpanKind
import dev.ostrom.core.telemetry.trace.StatusCode
import dev.ostrom.core.telemetry.trace.currentSpanContext
import dev.ostrom.server.kernel.auth.RejectingTokenAuthenticator
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.http.HealthCheck
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TracingTest {
    private val telemetry = RecordingTelemetry()
    private val lines = mutableListOf<String>()

    private object ProbeModule : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.get("/probe/{id}") {
                call.respondText(currentSpanContext()?.traceIdHex ?: "none")
            }
            route.get("/probe/crash") { throw IllegalStateException("boom") }
            route.get("/probe/silent") {}
        }
    }

    private class Up : HealthCheck {
        override val name: String = "database"
        override suspend fun isUp(): Boolean = true
    }

    private fun withServer(withTelemetry: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            val metrics = OstromMetrics(telemetry.meter)
            val koin = koinApplication {
                modules(module {
                    single<Logger> {
                        TelemetryLogger(JsonLogger(LogLevel.INFO, sink = { lines += it }), telemetry, LogLevel.INFO)
                    }
                    single<TokenAuthenticator> { RejectingTokenAuthenticator() }
                    single<HealthCheck>(named("database")) { Up() }
                    if (withTelemetry) {
                        single<Telemetry> { telemetry }
                        single { metrics }
                    }
                })
            }.koin
            application { configureHttp(koin, listOf(ProbeModule)) }
            block()
        }

    @Test
    fun aRequestContinuesTheCallersTraceAndNamesItsSpanAfterTheRoute() = withServer {
        val parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        val response = client.get("/probe/42?token=secret") { header(SpanContext.TRACEPARENT_HEADER, parent) }

        assertEquals("0af7651916cd43dd8448eb211c80319c", response.bodyAsText())
        val span = telemetry.spans.single()
        assertEquals("GET /probe/{id}", span.name)
        assertEquals(SpanKind.SERVER, span.kind)
        assertEquals("0af7651916cd43dd8448eb211c80319c", span.context.traceIdHex)
        assertEquals(0xb7ad6b7169203331uL.toLong(), span.parentSpanId)
        assertEquals(AttributeValue.Text("/probe/{id}"), span.attributes["http.route"])
        assertEquals(AttributeValue.Integer(200), span.attributes["http.response.status_code"])
        assertEquals(StatusCode.UNSET, span.status)
        assertEquals(span.context.toTraceparent(), response.headers[TRACE_RESPONSE_HEADER])
        assertTrue(span.attributes.entries.none { it.value.toString().contains("secret") })
    }

    @Test
    fun requestDurationsAreMeasuredByRouteTemplateAndTheLogLinksToTheTrace() = withServer {
        client.get("/probe/1")
        client.get("/probe/2")

        val attributes = Attributes.Builder()
            .put("http.request.method", "GET")
            .put("http.route", "/probe/{id}")
            .put("http.response.status_code", 200L)
            .build()
        assertEquals(2, telemetry.histogram("http.server.request.duration", attributes).count)
        assertEquals(0.0, telemetry.gauge("http.server.active_requests").single().value)
        val record = telemetry.logs.last { it.body == "request served" }
        assertEquals(telemetry.spans.last().context.traceIdHex, record.spanContext?.traceIdHex)
        assertTrue(lines.last().contains("\"trace_id\":\"${telemetry.spans.last().context.traceIdHex}\""))
    }

    @Test
    fun aServerErrorMarksTheSpanAndAnUnmatchedPathHasNoRoute() = withServer {
        assertEquals(HttpStatusCode.InternalServerError, client.get("/probe/crash").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/nowhere/at/all").status)

        val crash = telemetry.spans.single { it.name == "GET /probe/crash" }
        assertEquals(StatusCode.ERROR, crash.status)
        val unmatched = telemetry.spans.single { it.name == "GET $UNMATCHED_ROUTE" }
        assertEquals(AttributeValue.Integer(404), unmatched.attributes["http.response.status_code"])
        assertEquals(StatusCode.UNSET, unmatched.status)
    }

    @Test
    fun aHandlerThatAnswersNothingIsA404OnItsRoute() = withServer {
        assertEquals(HttpStatusCode.NotFound, client.get("/probe/silent").status)

        val span = telemetry.spans.single()
        assertEquals("GET /probe/silent", span.name)
        assertEquals(AttributeValue.Integer(404), span.attributes["http.response.status_code"])
    }

    @Test
    fun aTicketInTheUrlReachesNoSpanNorLog() = withServer {
        client.get("/probe/tkt_SECRET1?ticket=tkt_SECRET2")
        client.get("/nowhere/tkt_SECRET3")
        val routes = servedRoutes(atLeast = 2)

        val spans = telemetry.spans.flatMap { span -> listOf(span.name) + span.attributes.entries.map { "$it" } }
        val logs = telemetry.logs.flatMap { record -> listOf(record.body) + record.attributes.entries.map { "$it" } }
        val leaks = (spans + logs + lines).filter { "SECRET" in it }
        assertTrue(leaks.isEmpty(), "the URL leaked into: $leaks")
        assertEquals(AttributeValue.Text("/probe/{id}"), routes.first())
        assertEquals(setOf(AttributeValue.Text(UNMATCHED_ROUTE)), routes.drop(1).toSet())
    }

    /** The routes of the request lines, which are written after the answer is sent: waits for them, within a second. */
    private suspend fun servedRoutes(atLeast: Int): List<AttributeValue?> {
        repeat(50) {
            val routes = routesServed()
            if (routes.size >= atLeast) return routes
            delay(20.milliseconds)
        }
        return routesServed()
    }

    private fun routesServed(): List<AttributeValue?> =
        telemetry.logs.filter { it.body == "request served" }.map { it.attributes["route"] }

    @Test
    fun healthIsMeasuredButNeverTraced() = withServer {
        val response = client.get("/health")

        assertTrue(telemetry.spans.isEmpty())
        assertNull(response.headers[TRACE_RESPONSE_HEADER])
        val attributes = Attributes.Builder()
            .put("http.request.method", "GET")
            .put("http.route", "/health")
            .put("http.response.status_code", 200L)
            .build()
        assertEquals(1, telemetry.histogram("http.server.request.duration", attributes).count)
    }

    @Test
    fun withoutTelemetryInKoinNothingIsTraced() = withServer(withTelemetry = false) {
        val response = client.get("/probe/1")

        assertEquals("none", response.bodyAsText())
        assertNull(response.headers[TRACE_RESPONSE_HEADER])
        assertNotNull(lines.lastOrNull())
    }

    @Test
    fun anExceptionThatNoErrorPageHandlesIsRecordedAsA500() = testApplication {
        val metrics = OstromMetrics(telemetry.meter)
        application {
            installTelemetry(telemetry, metrics)
            routing { get("/boom") { throw IllegalStateException("boom") } }
        }
        try {
            client.get("/boom")
        } catch (_: IllegalStateException) {
            // The test engine may rethrow the handler's exception; the span is what matters here.
        }

        val span = telemetry.spans.single()
        assertEquals(StatusCode.ERROR, span.status)
        assertEquals(AttributeValue.Integer(500), span.attributes["http.response.status_code"])
    }

    @Test
    fun aCancelledCallIsA499NeitherANotFoundNorAnError() = testApplication {
        val metrics = OstromMetrics(telemetry.meter)
        application {
            installTelemetry(telemetry, metrics)
            routing { get("/gone") { throw CancellationException("the client went away") } }
        }
        try {
            client.get("/gone")
        } catch (_: CancellationException) {
            // The test engine hands the cancellation back to the caller; the span and the metric are what matter.
        }

        val span = telemetry.spans.single()
        assertEquals(StatusCode.UNSET, span.status)
        assertEquals(AttributeValue.Integer(499), span.attributes["http.response.status_code"])
    }

    /** A meter whose histograms fail, as a broken exporter could. */
    private object FailingHistograms : Meter by NoopMeter {
        override fun histogram(name: String, unit: String, description: String, boundaries: List<Double>): Histogram =
            object : Histogram {
                override fun record(value: Double, attributes: Attributes) {
                    error("broken")
                }
            }
    }

    @Test
    fun aMeasureThatFailsStillEndsTheSpan() = testApplication {
        val metrics = OstromMetrics(FailingHistograms)
        application {
            installTelemetry(telemetry, metrics)
            routing {
                get("/up") { call.respondText("up") }
                get("/health") { call.respondText("ok") }
            }
        }
        for (path in listOf("/up", "/health")) {
            try {
                client.get(path)
            } catch (_: IllegalStateException) {
                // The test engine may hand the measure's failure back to the caller; the span is what matters here.
            }
        }

        assertEquals("GET /up", telemetry.spans.single().name)
    }

    @Test
    fun anUnknownMethodIsRecordedAsOther() {
        assertEquals("GET", knownMethod("GET"))
        assertEquals("PATCH", knownMethod("PATCH"))
        assertEquals("_OTHER", knownMethod("BREW"))
    }

    @Test
    fun routeTemplatesDropKtorSelectors() {
        assertEquals("/v1/gates/{id}", routeTemplate("/(authenticate token)/v1/gates/{id}/(method:GET)"))
        assertEquals("/", routeTemplate("/(method:GET)"))
        assertEquals("/", routeTemplate(""))
        assertEquals(
            "/api/v1/secrets/{name}",
            routeTemplate("/(authenticate ostrom-token)/api/v1/secrets/{name}/[scope?]/[limit]/(method:PUT)"),
        )
        assertEquals("/a/(b/c]/[d)", routeTemplate("/a/(b/c]/[d)"))
    }
}
