package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.http.ApiError
import dev.ostrom.server.kernel.http.HealthCheck
import dev.ostrom.server.kernel.http.HealthResponse
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class HttpTest {
    private class FixedHealthCheck(override val name: String, private val up: Boolean) : HealthCheck {
        override suspend fun isUp(): Boolean = up
    }

    private class OneTokenAuthenticator : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? =
            if (token == "good-token") AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet()) else null
    }

    private class ProbeRefusedException : OstromException(ErrorKind.FORBIDDEN, "Probe refused.")

    private class ProbeBadRequestException : io.ktor.server.plugins.BadRequestException("Probe body unreadable.")

    private object ProbeModule : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.get("/probe/missing") { throw OstromException(ErrorKind.NOT_FOUND, "Probe not found.") }
            route.get("/probe/crash") { throw IllegalStateException("database password is hunter2") }
            route.get("/probe/refused") { throw ProbeRefusedException() }
            route.get("/probe/bad") { throw ProbeBadRequestException() }
            route.authenticated {
                get("/probe/me") { call.respondText(call.requirePrincipal().subjectId) }
            }
        }
    }

    private fun httpTest(databaseUp: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val koin = koinApplication {
            modules(module {
                single<Logger> { JsonLogger(LogLevel.ERROR, sink = {}) }
                single<TokenAuthenticator> { OneTokenAuthenticator() }
                single<HealthCheck>(named("database")) { FixedHealthCheck("database", databaseUp) }
            })
        }.koin
        application { configureHttp(koin, listOf(ProbeModule)) }
        block()
    }

    private suspend fun HttpResponse.error(): ApiError = OstromJson.decodeFromString(bodyAsText())

    @Test
    fun healthIsOkWhenEveryCheckPasses() = httpTest {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val health = OstromJson.decodeFromString<HealthResponse>(response.bodyAsText())
        assertEquals("ok", health.status)
        assertEquals(mapOf("database" to "ok"), health.checks)
    }

    @Test
    fun healthIs503WhenTheDatabaseIsDown() = httpTest(databaseUp = false) {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("degraded", OstromJson.decodeFromString<HealthResponse>(response.bodyAsText()).status)
    }

    @Test
    fun unknownRouteIsAJson404() = httpTest {
        val response = client.get("/nowhere")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", response.error().error.code)
    }

    @Test
    fun domainErrorKeepsItsKindAndMessage() = httpTest {
        val response = client.get("/probe/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Probe not found.", response.error().error.message)
    }

    @Test
    fun aSubclassOfADomainErrorKeepsItsKind() = httpTest {
        // Kotlin/Native: StatusPages does not pick the nearest parent class; the subclass must not answer 500.
        val response = client.get("/probe/refused")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("Probe refused.", response.error().error.message)
        assertEquals(HttpStatusCode.BadRequest, client.get("/probe/bad").status)
    }

    @Test
    fun unexpectedErrorIsA500WithoutItsCause() = httpTest {
        val response = client.get("/probe/crash")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertFalse("hunter2" in body)
        assertEquals("internal_error", response.error().error.code)
    }

    @Test
    fun requestIdIsEchoedAndReportedInErrors() = httpTest {
        val response = client.get("/probe/missing") { header(HttpHeaders.XRequestId, "req-123") }
        assertEquals("req-123", response.headers[HttpHeaders.XRequestId])
        assertEquals("req-123", response.error().error.requestId)
    }

    @Test
    fun generatesARequestIdWhenNoneIsSent() = httpTest {
        assertNotNull(client.get("/health").headers[HttpHeaders.XRequestId])
    }

    @Test
    fun protectedRouteRequiresAToken() = httpTest {
        val missing = client.get("/probe/me")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("unauthorized", missing.error().error.code)
        assertEquals("Bearer", missing.headers[HttpHeaders.WWWAuthenticate])

        val rejected = client.get("/probe/me") { bearerAuth("bad-token") }
        assertEquals(HttpStatusCode.Unauthorized, rejected.status)

        val accepted = client.get("/probe/me") { bearerAuth("good-token") }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals("user-1", accepted.bodyAsText())
    }
}
