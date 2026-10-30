// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.http.ApiError
import dev.ostrom.server.kernel.http.DEFAULT_BODY_LIMIT
import dev.ostrom.server.kernel.http.bodyLimit
import dev.ostrom.server.kernel.http.HealthCheck
import dev.ostrom.server.kernel.http.HealthResponse
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.http.httpStatus
import dev.ostrom.server.kernel.http.isValidRequestId
import dev.ostrom.server.kernel.http.limitedBody
import dev.ostrom.server.kernel.http.requestFields
import dev.ostrom.server.kernel.http.routeTemplate
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import io.github.smyrgeorge.sqlx4k.SQLError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalAtomicApi::class)
class HttpTest {
    private class FixedHealthCheck(override val name: String, private val up: Boolean?) : HealthCheck {
        override suspend fun isUp(): Boolean = when (up) {
            null -> throw CancellationException("probe cancelled")
            else -> up
        }
    }

    private class ThrowingHealthCheck : HealthCheck {
        override val name: String = "cache"

        override suspend fun isUp(): Boolean = error("cache unreachable")
    }

    private class OneTokenAuthenticator : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? =
            if (token == "good-token") AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet()) else null
    }

    private class OneMemberResolver : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
            if (orgId == ORG) OrgPrincipal(ORG, MEMBER, OrgRole.APPROVER) else null
    }

    private class ProbeGoneException : OstromException(ErrorKind.NOT_FOUND, "Probe gone.")

    @Serializable
    private data class ProbeBody(val name: String)

    private object ProbeModule : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.get("/probe/missing") { throw OstromException(ErrorKind.NOT_FOUND, "Probe not found.") }
            route.get("/probe/subclass") { throw ProbeGoneException() }
            route.get("/probe/quota") { throw OstromException(ErrorKind.PAYMENT_REQUIRED, "Plan limit reached.") }
            route.get("/probe/crash") { throw IllegalStateException("database password is hunter2") }
            route.get("/probe/gone") { throw NotFoundException("internal detail") }
            route.get("/probe/huge") { throw PayloadTooLargeException(1) }
            route.get("/probe/duplicate") {
                throw SQLError(SQLError.Code.Database, kind = SQLError.Kind.UniqueViolation)
            }
            route.get("/probe/deadlock") {
                throw SQLError(
                    SQLError.Code.Database,
                    "Key (email)=(alice@example.com) is locked",
                    kind = SQLError.Kind.Deadlock,
                )
            }
            route.get("/probe/cancelled") { throw CancellationException("client gone") }
            route.get("/probe/unprotected-me") { call.respondText(call.requirePrincipal().subjectId) }
            route.post("/probe/echo") { call.respondText(call.receive<ProbeBody>().name) }
            route.get("/a/{id}/{ticket}/{path...}") { call.respondText("page") }
            route.bodyLimit(SMALL_LIMIT) {
                post("/probe/small") { call.respondText(call.receiveText()) }
            }
            route.bodyLimit(LARGE_LIMIT) {
                post("/probe/large") { call.respondText("${call.receiveText().length}") }
                bodyLimit(SMALL_LIMIT) {
                    post("/probe/large/tiny") { call.respondText(call.receiveText()) }
                }
            }
            route.get("/l/{slug}/{path...}") { call.respondText("page") }
            route.authenticated {
                get("/probe/me") { call.respondText(call.requirePrincipal().subjectId) }
                get("/probe/orgs/{org}") {
                    val member = call.requireOrgPrincipal(koin.get(), call.parameters["org"]?.let { Uuid.parse(it) })
                    call.respondText("${member.memberId} ${member.role}")
                }
            }
        }
    }

    // Written by the server's threads, read by the test: an atomic snapshot list.
    private val written = AtomicReference(emptyList<String>())
    private val lines: List<String> get() = written.load()

    private fun write(line: String) {
        while (true) {
            val current = written.load()
            if (written.compareAndSet(current, current + line)) return
        }
    }

    private fun probeServer(health: List<HealthCheck>? = null, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            val koin = koinApplication {
                modules(
                    module {
                        single<Logger> { JsonLogger(LogLevel.DEBUG, sink = ::write) }
                        single<TokenAuthenticator> { OneTokenAuthenticator() }
                        single<PrincipalOrgResolver> { OneMemberResolver() }
                        (health ?: listOf(FixedHealthCheck("database", true))).forEach { check ->
                            single<HealthCheck>(named(check.name)) { check }
                        }
                    },
                )
            }.koin
            application { configureHttp(koin, listOf(ProbeModule)) }
            block()
        }

    private suspend fun HttpResponse.error(): ApiError = OstromJson.decodeFromString(bodyAsText())

    private fun logged(message: String): Boolean = lines.any { "\"msg\":\"$message\"" in it }

    /** The request line is written after the answer is sent: wait for it, within a second. */
    private suspend fun requestLines(count: Int): List<String> {
        repeat(50) {
            val found = lines.filter { "request served" in it }
            if (found.size >= count) return found
            delay(20.milliseconds)
        }
        return lines.filter { "request served" in it }
    }

    @Test
    fun healthIsOkWhenEveryCheckPasses() = probeServer {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val health = OstromJson.decodeFromString<HealthResponse>(response.bodyAsText())
        assertEquals(HealthResponse("ok", "0.1.0-dev", mapOf("database" to "ok")), health)
    }

    @Test
    fun healthIsAlsoServedAtItsContractPath() = probeServer {
        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", OstromJson.decodeFromString<HealthResponse>(response.bodyAsText()).status)
    }

    @Test
    fun healthIs503WhenACheckIsDownOrFails() =
        probeServer(listOf(FixedHealthCheck("database", false), ThrowingHealthCheck())) {
            val response = client.get("/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val health = OstromJson.decodeFromString<HealthResponse>(response.bodyAsText())
            val expected = HealthResponse("degraded", "0.1.0-dev", mapOf("database" to "down", "cache" to "down"))
            assertEquals(expected, health)
        }

    @Test
    fun aCancelledHealthCheckIsNotReportedAsAnError() = probeServer(listOf(FixedHealthCheck("database", null))) {
        val status = try {
            client.get("/health").status
        } catch (expected: Exception) {
            null
        }
        assertNotEquals(HttpStatusCode.ServiceUnavailable, status, "A cancellation is not a failing check")
        assertFalse(logged("unexpected failure"), "A cancellation is not logged as a defect")
    }

    @Test
    fun aCancelledCallIsNotAnsweredAsAnError() = probeServer {
        val status = try {
            client.get("/probe/cancelled").status
        } catch (expected: Exception) {
            null
        }
        assertNotEquals(HttpStatusCode.NotFound, status)
        assertFalse(logged("unexpected failure"), "A cancellation is not logged as a defect")
    }

    @Test
    fun unknownRouteIsAJson404() = probeServer {
        val response = client.get("/nowhere")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", response.error().error.type)
    }

    @Test
    fun wrongMethodIsAJson405() = probeServer {
        val response = client.post("/health")
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals("method_not_allowed", response.error().error.type)
    }

    @Test
    fun domainErrorKeepsItsKindAndMessage() = probeServer {
        val response = client.get("/probe/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":{"type":"not_found","message":"Probe not found."}}""", response.bodyAsText())
    }

    @Test
    fun domainErrorSubclassKeepsItsKindAndMessage() = probeServer {
        val response = client.get("/probe/subclass")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Probe gone.", response.error().error.message)
    }

    @Test
    fun aPlanLimitIsA402() = probeServer {
        val response = client.get("/probe/quota")
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
        assertEquals("payment_required", response.error().error.type)
    }

    @Test
    fun ktorRefusalsKeepTheirStatus() = probeServer {
        val gone = client.get("/probe/gone")
        assertEquals(HttpStatusCode.NotFound, gone.status)
        assertEquals("Not found.", gone.error().error.message)
        val huge = client.get("/probe/huge")
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
        assertEquals("payload_too_large", huge.error().error.type)
    }

    @Test
    fun aBodyThatIsNotJsonIsA415AndMalformedJsonA400() = probeServer {
        val text = client.post("/probe/echo") {
            contentType(ContentType.Text.Plain)
            setBody("name")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, text.status)
        assertEquals("unsupported_media_type", text.error().error.type)

        val malformed = client.post("/probe/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "secret-in-body""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals("invalid_request", malformed.error().error.type)
        assertTrue(logged("malformed request"))
        assertFalse(lines.any { "secret-in-body" in it }, "The body is never logged")

        val valid = client.post("/probe/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ostrom"}""")
        }
        assertEquals("ostrom", valid.bodyAsText())
    }

    @Test
    fun aUniqueViolationIsA409AndAnyOtherDatabaseFailureA500() = probeServer {
        val duplicate = client.get("/probe/duplicate")
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("conflict", duplicate.error().error.type)

        val deadlock = client.get("/probe/deadlock")
        assertEquals(HttpStatusCode.InternalServerError, deadlock.status)
        val line = lines.single { "database failure" in it }
        assertTrue("\"error\":\"SQLError\"" in line, line)
        assertTrue("\"sql_kind\":\"Deadlock\"" in line, line)
        assertFalse("alice@example.com" in line, "A database error message quotes row values: never logged")
    }

    @Test
    fun unexpectedErrorIsA500WithoutItsCause() = probeServer {
        val response = client.get("/probe/crash")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertFalse("hunter2" in response.bodyAsText())
        assertEquals("internal_error", response.error().error.type)
        val line = lines.single { "unexpected failure" in it }
        assertTrue("\"error\":\"IllegalStateException\"" in line, line)
        assertFalse("hunter2" in line, "An unexpected error's message is never logged")
    }

    @Test
    fun everyKindHasItsStatus() {
        val expected = mapOf(
            ErrorKind.INVALID_REQUEST to 400,
            ErrorKind.UNAUTHORIZED to 401,
            ErrorKind.PAYMENT_REQUIRED to 402,
            ErrorKind.FORBIDDEN to 403,
            ErrorKind.NOT_FOUND to 404,
            ErrorKind.CONFLICT to 409,
            ErrorKind.PAYLOAD_TOO_LARGE to 413,
            ErrorKind.UNSUPPORTED_MEDIA_TYPE to 415,
            ErrorKind.RATE_LIMITED to 429,
            ErrorKind.UNAVAILABLE to 503,
            ErrorKind.INTERNAL to 500,
        )
        assertEquals(expected, ErrorKind.entries.associateWith { it.httpStatus.value })
    }

    @Test
    fun requestIdIsEchoedOrReplaced() = probeServer {
        val kept = client.get("/probe/missing") { header(HttpHeaders.XRequestId, "req-123") }
        assertEquals("req-123", kept.headers[HttpHeaders.XRequestId])
        val replaced = client.get("/health") { header(HttpHeaders.XRequestId, "bad id") }
        assertNotEquals("bad id", replaced.headers[HttpHeaders.XRequestId])
        assertNotNull(client.get("/health").headers[HttpHeaders.XRequestId])
    }

    @Test
    fun requestIdsAreShortAndPlain() {
        assertTrue(isValidRequestId("a-b_C9"))
        assertFalse(isValidRequestId(""))
        assertFalse(isValidRequestId("a".repeat(129)))
        assertTrue(isValidRequestId("a".repeat(128)))
        assertFalse(isValidRequestId("a b"))
    }

    @Test
    fun everyRequestIsLoggedOnceWithoutItsQuery() = probeServer {
        client.get("/probe/missing?token=abc")
        client.get("/health")
        val requestLines = requestLines(2)
        assertEquals(2, requestLines.size)
        assertTrue("\"level\":\"INFO\"" in requestLines[0] && "\"status\":404" in requestLines[0])
        assertFalse("abc" in requestLines[0])
        assertTrue("\"level\":\"DEBUG\"" in requestLines[1])
    }

    @Test
    fun requestFieldsTolerateAMissingStatusOrStart() {
        val fields = requestFields("GET", "/x", status = null, start = null, requestId = null)
        assertEquals(
            mapOf("method" to "GET", "route" to "/x", "status" to null, "duration_ms" to null, "request_id" to null),
            fields,
        )
    }

    @Test
    fun aTicketOrAPublicLinkInThePathNeverReachesTheLogs() = probeServer {
        // S2-45: the path of an artifact page holds a one-hour ticket, a public link's path its permanent slug.
        val ticket = "ticket-0123456789abcdef"
        val slug = "slug-fedcba9876543210"
        assertEquals("page", client.get("/a/${Uuid.random()}/$ticket/index.html?token=query-secret").bodyAsText())
        assertEquals("page", client.get("/l/$slug/docs/page.html").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/nowhere/$ticket").status)
        val served = requestLines(3)
        assertEquals(3, served.size)
        assertTrue("\"route\":\"/a/{id}/{ticket}/{path...}\"" in served[0], served[0])
        assertTrue("\"route\":\"/l/{slug}/{path...}\"" in served[1], served[1])
        assertTrue("\"route\":\"unmatched\"" in served[2], served[2])
        for (secret in listOf(ticket, slug, "query-secret", "index.html", "page.html")) {
            assertFalse(lines.any { secret in it }, "$secret must never be logged")
        }
        assertFalse(lines.any { "\"path\"" in it }, "No log line carries a request path")
    }

    private fun chunked(text: String) = object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.Json

        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeStringUtf8(text)
        }
    }

    @Test
    fun aBodyOverTheDefaultLimitIsA413BeforeItsMediaTypeIsChecked() = probeServer {
        // S2-46: 1 MiB by default, refused on its declared length, and ahead of the 415 of a body that is not JSON.
        val tooLarge = "x".repeat((DEFAULT_BODY_LIMIT + 1).toInt())
        val json = client.post("/probe/echo") {
            contentType(ContentType.Application.Json)
            setBody(tooLarge)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, json.status)
        assertEquals("payload_too_large", json.error().error.type)
        val text = client.post("/probe/echo") {
            contentType(ContentType.Text.Plain)
            setBody(tooLarge)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, text.status)
        val atTheLimit = client.post("/probe/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"${"x".repeat((DEFAULT_BODY_LIMIT - 11).toInt())}"}""")
        }
        assertEquals(HttpStatusCode.OK, atTheLimit.status)
    }

    @Test
    fun aBodyWithoutALengthIsCutOneBytePastTheLimit() = probeServer {
        val over = client.post("/probe/small") { setBody(chunked("x".repeat(SMALL_LIMIT.toInt() + 1))) }
        assertEquals(HttpStatusCode.PayloadTooLarge, over.status)
        val under = client.post("/probe/small") { setBody(chunked("x".repeat(SMALL_LIMIT.toInt()))) }
        assertEquals("x".repeat(SMALL_LIMIT.toInt()), under.bodyAsText())
    }

    @Test
    fun aRouteDeclaresItsOwnLimitAndTheInnermostWins() = probeServer {
        val large = "x".repeat((DEFAULT_BODY_LIMIT + 1).toInt())
        val accepted = client.post("/probe/large") {
            contentType(ContentType.Text.Plain)
            setBody(large)
        }
        assertEquals("${large.length}", accepted.bodyAsText())
        val tiny = client.post("/probe/large/tiny") {
            contentType(ContentType.Text.Plain)
            setBody("x".repeat(SMALL_LIMIT.toInt() + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, tiny.status)
        val served = requestLines(2)
        assertTrue("\"route\":\"/probe/large\"" in served[0], served[0])
        assertTrue("\"route\":\"/probe/large/tiny\"" in served[1], served[1])
    }

    @Test
    fun aBodyThatIsNotTheRequestChannelIsLeftAlone() = runTest {
        assertEquals("already read", limitedBody("already read", 1, 100))
    }

    @Test
    fun aBodyLimitIsPositive() = testApplication {
        var refused: IllegalArgumentException? = null
        routing { refused = assertFailsWith<IllegalArgumentException> { bodyLimit(0) {} } }
        startApplication()
        assertNotNull(refused)
    }

    @Test
    fun aRouteTemplateDropsKtorSelectors() {
        assertEquals("/", routeTemplate(""))
        assertEquals("/", routeTemplate("/(authenticate ostrom-token)/(method:GET)"))
        assertEquals(
            "/api/v1/secrets/{name}",
            routeTemplate("/(authenticate ostrom-token)/api/v1/secrets/{name}/[scope?]/[limit]/(method:PUT)"),
        )
        assertEquals("/a/(b/c]/[d)", routeTemplate("/a/(b/c]/[d)"))
    }

    @Test
    fun protectedRouteRequiresAValidBearerToken() = probeServer {
        val missing = client.get("/probe/me")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("unauthorized", missing.error().error.type)
        assertEquals("Bearer", missing.headers[HttpHeaders.WWWAuthenticate])

        assertEquals(HttpStatusCode.Unauthorized, client.get("/probe/me") { bearerAuth("bad-token") }.status)
        val otherScheme = client.get("/probe/me") { header(HttpHeaders.Authorization, "Basic good-token") }
        assertEquals(HttpStatusCode.Unauthorized, otherScheme.status)
        val empty = client.get("/probe/me") { header(HttpHeaders.Authorization, "Bearer") }
        assertEquals(HttpStatusCode.Unauthorized, empty.status)

        val accepted = client.get("/probe/me") { header(HttpHeaders.Authorization, "bearer good-token") }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals("user-1", accepted.bodyAsText())
    }

    @Test
    fun aPrincipalOutsideAnAuthenticatedRouteIsA401() = probeServer {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/probe/unprotected-me").status)
    }

    @Test
    fun anOrgRouteResolvesTheMemberOrHidesTheOrg() = probeServer {
        val member = client.get("/probe/orgs/$ORG") { bearerAuth("good-token") }
        assertEquals("$MEMBER APPROVER", member.bodyAsText())

        val stranger = client.get("/probe/orgs/${Uuid.random()}") { bearerAuth("good-token") }
        assertEquals(HttpStatusCode.NotFound, stranger.status)
        assertEquals("Organization not found.", stranger.error().error.message)
    }

    private companion object {
        val ORG = Uuid.parse("0f0e0d0c-0b0a-0908-0706-050403020100")
        val MEMBER = Uuid.parse("00010203-0405-0607-0809-0a0b0c0d0e0f")
        const val SMALL_LIMIT = 16L
        const val LARGE_LIMIT = 2L shl 20
    }
}
