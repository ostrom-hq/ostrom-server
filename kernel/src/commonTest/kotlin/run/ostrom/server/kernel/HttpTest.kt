// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgProfile
import run.ostrom.server.kernel.auth.OrgRequiredException
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.RejectingPrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.kernel.http.DEFAULT_BODY_LIMIT
import run.ostrom.server.kernel.http.HealthCheck
import run.ostrom.server.kernel.http.HealthResponse
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.kernel.http.Readiness
import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.http.httpStatus
import run.ostrom.server.kernel.http.isBodilessUpgrade
import run.ostrom.server.kernel.http.isValidRequestId
import run.ostrom.server.kernel.http.limitedBody
import run.ostrom.server.kernel.http.requestFields
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.network.TrustedProxies
import run.ostrom.server.kernel.observability.UNMATCHED_ROUTE
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
import io.ktor.http.headersOf
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.origin
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
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
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
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    private class ProbeNotYoursException : OstromException(ErrorKind.FORBIDDEN, "Not yours.", "probe_not_yours")

    @Serializable
    private data class ProbeBody(val name: String)

    private object ProbeModule : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.get("/probe/missing") { throw OstromException(ErrorKind.NOT_FOUND, "Probe not found.") }
            route.get("/probe/subclass") { throw ProbeGoneException() }
            route.get("/probe/typed") { throw ProbeNotYoursException() }
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
                    sqlState = "40P01",
                    kind = SQLError.Kind.Deadlock,
                )
            }
            route.get("/probe/pool") { throw SQLError(SQLError.Code.PoolTimedOut, "pool of alice@example.com") }
            route.get("/probe/origin") { call.respondText(call.request.origin.remoteAddress) }
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
                get("/probe/named-org") { call.respondText(call.requirePrincipal().namedOrg.toString()) }
                get("/probe/org-required") { throw OrgRequiredException() }
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

    private fun probeServer(
        health: List<HealthCheck>? = null,
        readiness: Readiness? = null,
        proxies: TrustedProxies? = null,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) =
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
                        readiness?.let { value -> single { value } }
                        proxies?.let { value -> single { value } }
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
    fun readyIsOkWhenEveryCheckPasses() = probeServer {
        val response = client.get("/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        val ready = OstromJson.decodeFromString<HealthResponse>(response.bodyAsText())
        assertEquals(HealthResponse("ready", "0.1.0-dev", mapOf("database" to "ok")), ready)
    }

    @Test
    fun readyIs503WhenACheckIsDown() = probeServer(listOf(FixedHealthCheck("database", false))) {
        val response = client.get("/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("degraded", OstromJson.decodeFromString<HealthResponse>(response.bodyAsText()).status)
    }

    @Test
    fun aDrainingServerIsNotReadyButStaysHealthy() {
        val readiness = Readiness()
        probeServer(readiness = readiness) {
            assertEquals(HttpStatusCode.OK, client.get("/ready").status)
            assertFalse(readiness.isDraining)
            readiness.drain()
            assertTrue(readiness.isDraining)
            val response = client.get("/ready")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val draining = OstromJson.decodeFromString<HealthResponse>(response.bodyAsText())
            assertEquals(HealthResponse("draining", "0.1.0-dev", emptyMap()), draining)
            assertEquals(HttpStatusCode.OK, client.get("/health").status, "Draining is not a failure")
        }
    }

    @Test
    fun probesAreLoggedAtDebug() = probeServer {
        client.get("/ready")
        val line = requestLines(1).single()
        assertTrue("\"level\":\"DEBUG\"" in line, line)
    }

    @Test
    fun aPeerThatIsNotATrustedProxyKeepsItsAddress() = probeServer(proxies = TrustedProxies.parse("10.0.0.0/8")) {
        val response = client.get("/probe/origin") { header("X-Forwarded-For", "198.51.100.1") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText() != "198.51.100.1", "The test engine's peer is not a trusted proxy")
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
    fun aDomainErrorWithItsOwnTypeKeepsTheStatusOfItsKind() = probeServer {
        val response = client.get("/probe/typed")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("""{"error":{"type":"probe_not_yours","message":"Not yours."}}""", response.bodyAsText())
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
        assertTrue("\"sql_code\":\"Database\"" in line && "\"sql_state\":\"40P01\"" in line, line)
        assertFalse("alice@example.com" in line, "A database error message quotes row values: never logged")

        // A failure of the driver itself, which PostgreSQL never saw: its code says which (OrgsReadsBenchTest's 500
        // of server#283 logged only `Other`).
        assertEquals(HttpStatusCode.InternalServerError, client.get("/probe/pool").status)
        val pool = lines.last { "database failure" in it }
        assertTrue("\"sql_code\":\"PoolTimedOut\"" in pool && "sql_state" !in pool, pool)
        assertFalse("alice@example.com" in pool, pool)
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
            ErrorKind.ORG_REQUIRED to 409,
            ErrorKind.PAYLOAD_TOO_LARGE to 413,
            ErrorKind.UNSUPPORTED_MEDIA_TYPE to 415,
            ErrorKind.RATE_LIMITED to 429,
            ErrorKind.GONE to 410,
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
        client.get("/probe/missing?token=qqsecretqq")
        client.get("/health")
        val requestLines = requestLines(2)
        assertEquals(2, requestLines.size)
        assertTrue("\"level\":\"INFO\"" in requestLines[0] && "\"status\":404" in requestLines[0])
        // Not "abc": a random request id can contain hex letters.
        assertFalse("qqsecretqq" in requestLines[0])
        assertTrue("\"level\":\"DEBUG\"" in requestLines[1])
    }

    @Test
    fun requestFieldsTolerateAMissingStatusOrStart() {
        val fields = requestFields("GET", "/x", status = null, start = null, requestId = null, span = null)
        assertEquals(
            mapOf(
                "method" to "GET",
                "route" to "/x",
                "status" to null,
                "duration_ms" to null,
                "request_id" to null,
                "trace_id" to null,
                "span_id" to null,
                "trace_flags" to null,
            ),
            fields,
        )
    }

    @Test
    fun aNotFoundIsLoggedOnce() = probeServer {
        // The JSON of a 404 is sent from within the bare 404: both sends end, one line is written.
        val missing = client.get("/nowhere") { header(HttpHeaders.XRequestId, "once-404") }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("page", client.get("/l/slug/page") { header(HttpHeaders.XRequestId, "after-404") }.bodyAsText())
        // Waits for a third line, which must never come, so that a late second line of the 404 would be seen.
        requestLines(3)
        assertTrue(lines.any { "after-404" in it }, lines.joinToString("\n"))
        assertEquals(1, lines.count { "\"request_id\":\"once-404\"" in it }, lines.joinToString("\n"))
    }

    @Test
    fun aTicketOrAPublicLinkInThePathNeverReachesTheLogs() = probeServer {
        // S2-45: the path of an artifact page holds a one-hour ticket, a public link's path its permanent slug.
        val ticket = "ticket-0123456789abcdef"
        val slug = "slug-fedcba9876543210"
        assertEquals("page", client.get("/a/${Uuid.random()}/$ticket/index.html?token=query-secret").bodyAsText())
        assertEquals("page", client.get("/l/$slug/docs/page.html").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/nowhere/$ticket").status)
        val requests = requestLines(3)
        assertEquals(3, requests.size, requests.joinToString("\n"))
        val served = requests.joinToString("\n")
        // Ktor names a tail segment `{...}`, whatever the route calls it.
        for (route in listOf("/a/{id}/{ticket}/{...}", "/l/{slug}/{...}", UNMATCHED_ROUTE)) {
            assertTrue("\"route\":\"$route\"" in served, served)
        }
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
    fun onlyAnUpgradeWithoutABodyKeepsItsRawStream() {
        // S2-46: a WebSocket's stream never ends, the engine reads it through receive; a body is capped all the same.
        assertTrue(isBodilessUpgrade(headersOf(HttpHeaders.Upgrade, "websocket")))
        assertFalse(isBodilessUpgrade(headersOf()))
        val upgrade = HttpHeaders.Upgrade to listOf("h2c")
        assertFalse(isBodilessUpgrade(headersOf(upgrade, HttpHeaders.ContentLength to listOf("5"))))
        assertFalse(isBodilessUpgrade(headersOf(upgrade, HttpHeaders.TransferEncoding to listOf("chunked"))))
    }

    @Test
    fun aBodilessUpgradeKeepsItsStreamAndABodyIsCappedAllTheSame() = runTest {
        // The engine hands a WebSocket's stream, which never ends, to the upgrade through receive: it is not read.
        val stream = ByteChannel()
        assertSame(stream, limitedBody(stream, 1, headersOf(HttpHeaders.Upgrade, "websocket")))
        val withBody = headersOf(HttpHeaders.Upgrade to listOf("h2c"), HttpHeaders.ContentLength to listOf("5"))
        assertFailsWith<PayloadTooLargeException> { limitedBody(ByteReadChannel("hello"), 1, withBody) }
    }

    @Test
    fun aBodyThatIsNotTheRequestChannelIsLeftAlone() = runTest {
        assertEquals("already read", limitedBody("already read", 1, headersOf(HttpHeaders.ContentLength, "100")))
    }

    @Test
    fun aBodyLimitIsPositive() = testApplication {
        var refused: IllegalArgumentException? = null
        routing { refused = assertFailsWith<IllegalArgumentException> { bodyLimit(0) {} } }
        startApplication()
        assertNotNull(refused)
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

    @Test
    fun theRejectingResolverKnowsNoOrganization() = runTest {
        assertNull(RejectingPrincipalOrgResolver().profile(ORG))
    }

    @Test
    fun anOrganizationIsShownToItsMemberByItsNamesAndPicture() {
        val acme = OrgProfile(ORG, "acme", "Acme", "https://avatars.githubusercontent.com/u/7", linkedToGitHub = true)

        assertEquals(ORG, acme.id)
        assertEquals("acme" to "Acme", acme.slug to acme.name)
        assertEquals("https://avatars.githubusercontent.com/u/7", acme.avatarUrl)
        assertTrue(acme.linkedToGitHub)
    }

    @Test
    fun theOrgHeaderReachesTheResolverAndAnUnnamedOrgIsA409() = probeServer {
        val named = client.get("/probe/named-org") {
            bearerAuth("good-token")
            header(ORG_HEADER, ORG.toString())
        }
        assertEquals(ORG.toString(), named.bodyAsText())
        assertEquals("null", client.get("/probe/named-org") { bearerAuth("good-token") }.bodyAsText())

        val unnamed = client.get("/probe/org-required") { bearerAuth("good-token") }
        assertEquals(HttpStatusCode.Conflict, unnamed.status)
        assertEquals("org_required", unnamed.error().error.type)
    }

    private companion object {
        val ORG = Uuid.parse("0f0e0d0c-0b0a-0908-0706-050403020100")
        val MEMBER = Uuid.parse("00010203-0405-0607-0809-0a0b0c0d0e0f")
        const val SMALL_LIMIT = 16L
        const val LARGE_LIMIT = 2L shl 20
    }
}
