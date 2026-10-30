// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.auth.ALL_USER_SCOPES
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireScope
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The browser session cookie, its CSRF token, and the scopes a route requires (`requireScope`). */
class BrowserSessionAuthenticationTest {
    /** One bearer token (`good-token`, scope `probe:read`), one browser session (`good-session`, `good-csrf`). */
    private class ProbeAuthenticator : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
            "good-token" -> AuthenticatedPrincipal("token-user", PrincipalKind.USER, setOf("probe:read"))
            else -> null
        }

        override suspend fun authenticateSession(
            sessionToken: String,
            csrfToken: String?,
            requireCsrf: Boolean,
        ): AuthenticatedPrincipal? {
            val csrfHolds = !requireCsrf || csrfToken == "good-csrf"
            return if (sessionToken == "good-session" && csrfHolds) {
                AuthenticatedPrincipal("session-user", PrincipalKind.USER, setOf(ALL_USER_SCOPES))
            } else {
                null
            }
        }
    }

    /** Implements only bearer tokens: the default rejects every session. */
    private class TokensOnlyAuthenticator : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null
    }

    private object ProbeModule : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.authenticated {
                get("/probe/read") { call.respondText(call.requireScope("probe:read").subjectId) }
                post("/probe/write") { call.respondText(call.requireScope("probe:write").subjectId) }
            }
        }
    }

    private fun probeServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val koin = koinApplication {
            modules(
                module {
                    single<Logger> { JsonLogger(LogLevel.ERROR, sink = {}) }
                    single<TokenAuthenticator> { ProbeAuthenticator() }
                },
            )
        }.koin
        application { configureHttp(koin, listOf(ProbeModule)) }
        block()
    }

    @Test
    fun aBrowserSessionReadsWithoutCsrfAndWritesOnlyWithIt() = probeServer {
        val read = client.get("/probe/read") { cookie(SESSION_COOKIE, "good-session") }
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals("session-user", read.bodyAsText())

        val forged = client.post("/probe/write") { cookie(SESSION_COOKIE, "good-session") }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)

        val written = client.post("/probe/write") {
            cookie(SESSION_COOKIE, "good-session")
            header(CSRF_HEADER, "good-csrf")
        }
        assertEquals(HttpStatusCode.OK, written.status)
        assertEquals("session-user", written.bodyAsText())
    }

    @Test
    fun anUnknownOrEmptySessionCookieIsRefused() = probeServer {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/probe/read") { cookie(SESSION_COOKIE, "stolen") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/probe/read") { cookie(SESSION_COOKIE, "") }.status)
    }

    @Test
    fun aBearerTokenWinsOverACookieAndKeepsToItsScopes() = probeServer {
        val read = client.get("/probe/read") {
            bearerAuth("good-token")
            cookie(SESSION_COOKIE, "good-session")
        }
        assertEquals("token-user", read.bodyAsText())

        val write = client.post("/probe/write") {
            bearerAuth("good-token")
            cookie(SESSION_COOKIE, "good-session")
            header(CSRF_HEADER, "good-csrf")
        }
        assertEquals(HttpStatusCode.Forbidden, write.status)
    }

    @Test
    fun aUsersSessionAllowsEverythingButAnAgentOnlyItsListedScopes() {
        val session = AuthenticatedPrincipal("u", PrincipalKind.USER, setOf(ALL_USER_SCOPES))
        val agent = AuthenticatedPrincipal("s", PrincipalKind.AGENT, setOf(ALL_USER_SCOPES, "session:act"))
        val token = AuthenticatedPrincipal("u", PrincipalKind.USER, setOf("probe:read"))

        assertTrue(session.allows("orgs:write"))
        assertTrue(agent.allows("session:act"))
        assertFalse(agent.allows("orgs:write"))
        assertTrue(token.allows("probe:read"))
        assertFalse(token.allows("probe:write"))
    }

    @Test
    fun anAuthenticatorWithoutSessionsRejectsEveryCookie() = runTest {
        assertNull(TokensOnlyAuthenticator().authenticateSession("good-session", "good-csrf", requireCsrf = true))
    }
}
