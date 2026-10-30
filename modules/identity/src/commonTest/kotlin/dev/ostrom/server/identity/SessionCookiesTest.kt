// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import dev.ostrom.server.identity.fakes.CountingSecretGenerator
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.InMemoryWebSessionsRepository
import dev.ostrom.server.identity.fakes.SettableClock
import dev.ostrom.server.identity.presentation.IdentityConfig
import dev.ostrom.server.identity.presentation.sessionCookies
import io.ktor.http.renderSetCookieHeader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The names and attributes of the two session cookies (audits S2-08 and S2-12). */
class SessionCookiesTest {
    private val https = IdentityConfig("https://api.ostrom.run", "https://app.ostrom.run")
    private val http = IdentityConfig("http://localhost:8080", "http://localhost:5173")

    @Test
    fun overHttpsBothCookiesAreHostPrefixedSecureForTheWholeHostWithoutDomain() = runTest {
        val sessions = InMemoryWebSessionsRepository()
        val clock = SettableClock()
        val startSession = StartWebSessionUseCaseImpl(sessions, CountingSecretGenerator(), FakeHasher(), clock)
        val started = startSession(Uuid.random())

        val (session, csrf) = sessionCookies(started, https)
        assertEquals(listOf("__Host-ostrom_session", "__Host-ostrom_csrf"), listOf(session.name, csrf.name))
        assertEquals(listOf(started.secret, started.csrfToken), listOf(session.value, csrf.value))
        assertTrue(session.httpOnly && !csrf.httpOnly)
        listOf(session, csrf).forEach { cookie ->
            assertTrue(cookie.secure, cookie.name)
            assertEquals("/", cookie.path)
            assertNull(cookie.domain, "A __Host- cookie never names a Domain")
            assertTrue((cookie.maxAge ?: 0) > 0)
            val header = renderSetCookieHeader(cookie)
            assertTrue("Secure" in header && "Path=/" in header && "SameSite=Lax" in header, header)
            assertFalse("Domain" in header, header)
        }
    }

    @Test
    fun aPlainHttpDevelopmentServerKeepsThePlainNamesAndExpiresBoth() {
        val cleared = sessionCookies(null, http)
        assertEquals(listOf("ostrom_session", "ostrom_csrf"), cleared.map { it.name })
        assertTrue(cleared.all { it.value.isEmpty() && it.maxAge == 0 && !it.secure })
        assertEquals("__Host-", https.sessionCookiePrefix)
        assertEquals("", http.sessionCookiePrefix)
    }
}
