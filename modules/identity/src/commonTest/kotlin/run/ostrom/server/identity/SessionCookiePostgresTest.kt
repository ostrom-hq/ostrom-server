// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.SignInLink
import run.ostrom.server.identity.domain.SignInLinksRepository
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.presentation.CreateTokenRequest
import run.ostrom.server.identity.presentation.CreatedTokenResponse
import run.ostrom.server.identity.presentation.ExchangeSignInLinkRequest
import run.ostrom.server.identity.presentation.AccountResponse
import run.ostrom.server.identity.presentation.SignInLinkResponse
import run.ostrom.server.identity.presentation.SignedInResponse
import run.ostrom.server.kernel.auth.CSRF_COOKIE
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.kernel.auth.SignOutListener
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The browser session end to end (audit S2-08): cookies, CSRF and origin, sign-in links, sign-out, no-store. */
class SessionCookiePostgresTest {
    /** What the sign-out told the other modules. */
    private class RecordingSignOutListener : SignOutListener {
        val calls = mutableListOf<Pair<String, String?>>()

        override suspend fun signedOut(subjectId: String, credentialId: String?) {
            calls += subjectId to credentialId
        }
    }

    private val listener = RecordingSignOutListener()
    private val overrides = module { single<SignOutListener> { listener } }

    private suspend fun TestServer.signIn(): Pair<User, StartedSession> {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, "1", "ada@example.com", true, "Ada"),
        )
        return user to koin.get<StartWebSessionUseCase>()(user.id)
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun TestServer.exchange(token: String, origin: String? = TEST_CONSOLE_ORIGIN) =
        client.post("/api/v1/auth/link") {
            if (origin != null) header(HttpHeaders.Origin, origin)
            json(ExchangeSignInLinkRequest(token))
        }

    private fun HttpResponse.cookie(name: String) =
        headers.getAll(HttpHeaders.SetCookie).orEmpty().map(::parseServerSetCookieHeader).lastOrNull { it.name == name }

    @Test
    fun aStateChangingCookieRequestNeedsTheCsrfCookieTheSameHeaderAndTheConsolesOrigin() =
        serverTest(listOf(IdentityModule)) { server ->
            val (_, started) = server.signIn()
            val create = CreateTokenRequest("t", listOf("profile:read"))

            val good = server.client.post("/api/v1/tokens") {
                browserSession(started.secret, started.csrfToken)
                json(create)
            }
            assertEquals(HttpStatusCode.Created, good.status)
            val refused = listOf(
                // Another site's page: the browser sends its own origin.
                "https://evil.test" to "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}",
                // No origin at all.
                null to "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}",
                // The header without the readable cookie it must repeat.
                TEST_CONSOLE_ORIGIN to "$SESSION_COOKIE=${started.secret}",
            ).map { (origin, cookies) ->
                server.client.post("/api/v1/tokens") {
                    header(HttpHeaders.Cookie, cookies)
                    header(CSRF_HEADER, started.csrfToken)
                    if (origin != null) header(HttpHeaders.Origin, origin)
                    json(create)
                }.status
            }
            assertEquals(List(3) { HttpStatusCode.Unauthorized }, refused)

            // A bearer token (the CLI) is not a cookie: no CSRF, no origin.
            val secret = good.body<CreatedTokenResponse>().secret
            val me = server.client.get("/api/v1/auth/me") {
                bearerAuth(secret)
                header(HttpHeaders.Origin, "https://evil.test")
            }
            assertEquals(HttpStatusCode.OK, me.status)
        }

    @Test
    fun aSignInLinkIsExchangedOnceFromTheConsoleForBothCookies() = serverTest(listOf(IdentityModule)) { server ->
        val (user, started) = server.signIn()

        val created = server.client.post("/api/v1/auth/links") { browserSession(started.secret, started.csrfToken) }
        assertEquals(HttpStatusCode.Created, created.status)
        val link = created.body<SignInLinkResponse>()
        assertTrue(link.token.startsWith("opk_"))
        assertEquals("$TEST_CONSOLE_ORIGIN/yes#signin=${link.token}", link.url)
        val expiresIn = Instant.parse(link.expiresAt) - Clock.System.now()
        assertTrue(expiresIn > 14.minutes && expiresIn <= 15.minutes, link.expiresAt)

        assertEquals(HttpStatusCode.Forbidden, server.exchange(link.token, "https://evil.test").status)
        assertEquals(HttpStatusCode.Forbidden, server.exchange(link.token, null).status)

        // The refusals above did not use the link.
        val exchanged = server.exchange(link.token)
        assertEquals(HttpStatusCode.OK, exchanged.status)
        val signedIn = exchanged.body<SignedInResponse>()
        assertEquals("/yes", signedIn.redirect)
        val session = assertNotNull(exchanged.cookie(SESSION_COOKIE))
        val csrf = assertNotNull(exchanged.cookie(CSRF_COOKIE))
        assertTrue(session.httpOnly && !csrf.httpOnly)
        assertEquals(signedIn.csrfToken, csrf.value)
        assertFalse(session.value in exchanged.bodyAsText(), "The session's secret is never in a body")
        assertEquals("no-store", exchanged.headers[HttpHeaders.CacheControl])
        val profile = server.client.get("/api/v1/auth/me/account") { browserSession(session.value, null) }
        assertEquals(user.id.toString(), profile.body<AccountResponse>().id)

        val replay = server.exchange(link.token)
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertNull(replay.cookie(SESSION_COOKIE))
    }

    @Test
    fun anExpiredOrForeignLinkOpensNothingAndOnlyABrowserSessionMakesOne() =
        serverTest(listOf(IdentityModule)) { server ->
            val (user, started) = server.signIn()
            val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
            val expired = SignInLink(Uuid.random(), user.id, now - 20.minutes, now - 5.minutes)
            val hash = server.koin.get<SecretHasher>().hash("opk_expired")
            server.koin.get<SignInLinksRepository>().create(expired, hash)

            listOf("opk_expired", "opk_unknown", "osp_not_a_link").forEach { token ->
                assertEquals(HttpStatusCode.Unauthorized, server.exchange(token).status, token)
            }

            val token = server.client.post("/api/v1/tokens") {
                browserSession(started.secret, started.csrfToken)
                json(CreateTokenRequest("t", listOf("profile:read")))
            }.body<CreatedTokenResponse>().secret
            val withToken = server.client.post("/api/v1/auth/links") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, withToken.status)
            assertEquals(HttpStatusCode.Unauthorized, server.client.post("/api/v1/auth/links").status)
        }

    @Test
    fun signingOutRevokesExpiresBothCookiesAndTellsTheOtherModules() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val (user, started) = server.signIn()
            val second = server.koin.get<StartWebSessionUseCase>()(user.id)

            val logout = server.client.post("/api/v1/auth/logout") { browserSession(started.secret, started.csrfToken) }
            assertEquals(HttpStatusCode.NoContent, logout.status)
            val cleared = listOf(SESSION_COOKIE, CSRF_COOKIE).map { assertNotNull(logout.cookie(it), it) }
            assertTrue(cleared.all { it.value.isEmpty() && it.maxAge == 0 }, cleared.toString())
            assertEquals("no-store", logout.headers[HttpHeaders.CacheControl])
            val expected: List<Pair<String, String?>> = listOf(user.id.toString() to started.session.id.toString())
            assertEquals(expected, listener.calls)
            val after = server.client.get("/api/v1/auth/me") { browserSession(started.secret, null) }
            assertEquals(HttpStatusCode.Unauthorized, after.status)

            // `ostrom logout` with a token: the token is revoked and its phones dropped.
            val created = server.client.post("/api/v1/tokens") {
                browserSession(second.secret, second.csrfToken)
                json(CreateTokenRequest("t", listOf("profile:read")))
            }.body<CreatedTokenResponse>()
            val cli = server.client.post("/api/v1/auth/logout") { bearerAuth(created.secret) }
            assertEquals(HttpStatusCode.NoContent, cli.status)
            assertEquals(user.id.toString() to created.token.id, listener.calls.last())

            val everywhere = server.client.post("/api/v1/auth/logout/everywhere") {
                browserSession(second.secret, second.csrfToken)
            }
            assertEquals(HttpStatusCode.NoContent, everywhere.status)
            assertNotNull(everywhere.cookie(CSRF_COOKIE))
            assertEquals(user.id.toString() to null, listener.calls.last())
        }

    @Test
    fun identityAnswersAreNeverCachedAndOtherRoutesAreLeftAlone() = serverTest(listOf(IdentityModule)) { server ->
        val (_, started) = server.signIn()

        val me = server.client.get("/api/v1/auth/me") { browserSession(started.secret, null) }
        assertEquals("no-store", me.headers[HttpHeaders.CacheControl])
        val tokens = server.client.get("/api/v1/tokens") { browserSession(started.secret, null) }
        assertEquals("no-store", tokens.headers[HttpHeaders.CacheControl])
        assertEquals("no-store", server.client.get("/api/v1/auth/me").headers[HttpHeaders.CacheControl], "Errors too")
        assertNull(server.client.get("/health").headers[HttpHeaders.CacheControl])
    }
}
