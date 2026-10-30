// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.HOST_COOKIE_PREFIX
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.RejectingTokenAuthenticator
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The browser-session path of the token provider: what the kernel hands the authenticator (audit S2-08). */
class SessionCookieAuthenticationTest {
    /** What the provider passed, and a session that is valid whenever the CSRF token is the expected one. */
    private class RecordingAuthenticator(override val sessionCookiePrefix: String = "") : TokenAuthenticator {
        val calls = mutableListOf<List<Any?>>()

        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null

        override suspend fun authenticateSession(
            sessionToken: String,
            csrfToken: String?,
            requireCsrf: Boolean,
            origin: String?,
        ): AuthenticatedPrincipal? {
            calls += listOf(sessionToken, csrfToken, requireCsrf, origin)
            val allowed = !requireCsrf || (csrfToken == "csrf-1" && origin == "https://app.test")
            return if (allowed) AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet(), "session-1") else null
        }
    }

    private object Probe : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.authenticated {
                get("/probe/session") { call.respondText(call.requirePrincipal().subjectId) }
                post("/probe/session") { call.respondText(call.requirePrincipal().subjectId) }
            }
        }
    }

    private fun sessionTest(authenticator: RecordingAuthenticator, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            val koin = koinApplication {
                modules(
                    module {
                        single<Logger> { JsonLogger(LogLevel.WARN, sink = {}) }
                        single<TokenAuthenticator> { authenticator }
                    },
                )
            }.koin
            application { configureHttp(koin, listOf(Probe)) }
            block()
        }

    @Test
    fun aStateChangingRequestPassesTheCsrfHeaderOnlyWhenItRepeatsTheCookieAndTheOrigin() {
        val authenticator = RecordingAuthenticator()
        sessionTest(authenticator) {
            val good = client.post("/probe/session") {
                header(HttpHeaders.Cookie, "$SESSION_COOKIE=oss_1; $CSRF_COOKIE=csrf-1")
                header(CSRF_HEADER, "csrf-1")
                header(HttpHeaders.Origin, "https://app.test")
            }
            assertEquals(HttpStatusCode.OK, good.status)
            assertEquals(listOf("oss_1", "csrf-1", true, "https://app.test"), authenticator.calls.last())

            // The header alone (a page that guessed or leaked the token) does not count without the matching cookie.
            val noCookie = client.post("/probe/session") {
                header(HttpHeaders.Cookie, "$SESSION_COOKIE=oss_1")
                header(CSRF_HEADER, "csrf-1")
                header(HttpHeaders.Origin, "https://app.test")
            }
            assertEquals(HttpStatusCode.Unauthorized, noCookie.status)
            assertNull(authenticator.calls.last()[1])
            val otherCookie = client.post("/probe/session") {
                header(HttpHeaders.Cookie, "$SESSION_COOKIE=oss_1; $CSRF_COOKIE=csrf-2")
                header(CSRF_HEADER, "csrf-1")
            }
            assertEquals(HttpStatusCode.Unauthorized, otherCookie.status)
            assertEquals(listOf("oss_1", null, true, null), authenticator.calls.last())
        }
    }

    @Test
    fun aSafeRequestNeedsNeitherCsrfNorOrigin() {
        val authenticator = RecordingAuthenticator()
        sessionTest(authenticator) {
            val read = client.get("/probe/session") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=oss_1") }
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(listOf("oss_1", null, false, null), authenticator.calls.single())
        }
    }

    @Test
    fun overHttpsOnlyTheHostPrefixedCookiesCount() {
        val authenticator = RecordingAuthenticator(HOST_COOKIE_PREFIX)
        sessionTest(authenticator) {
            // S2-12: a cookie without the prefix (set by a sibling subdomain, say) is not a session at all.
            val plain = client.get("/probe/session") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=oss_1") }
            assertEquals(HttpStatusCode.Unauthorized, plain.status)
            assertEquals(0, authenticator.calls.size)

            val mixed = client.post("/probe/session") {
                header(HttpHeaders.Cookie, "$HOST_COOKIE_PREFIX$SESSION_COOKIE=oss_1; $CSRF_COOKIE=csrf-1")
                header(CSRF_HEADER, "csrf-1")
                header(HttpHeaders.Origin, "https://app.test")
            }
            assertEquals(HttpStatusCode.Unauthorized, mixed.status)
            assertEquals(listOf("oss_1", null, true, "https://app.test"), authenticator.calls.last())

            val prefixed = client.post("/probe/session") {
                val cookies = "$HOST_COOKIE_PREFIX$SESSION_COOKIE=oss_1; $HOST_COOKIE_PREFIX$CSRF_COOKIE=csrf-1"
                header(HttpHeaders.Cookie, cookies)
                header(CSRF_HEADER, "csrf-1")
                header(HttpHeaders.Origin, "https://app.test")
            }
            assertEquals(HttpStatusCode.OK, prefixed.status)
        }
    }

    @Test
    fun theDefaultAuthenticatorRefusesEverySession() = runTest {
        assertNull(RejectingTokenAuthenticator().authenticateSession("oss_1", "csrf-1", true, "https://app.test"))
        assertEquals("", RejectingTokenAuthenticator().sessionCookiePrefix, "Plain names by default")
    }
}
