// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.MagicLink
import dev.ostrom.server.identity.domain.MagicLinksRepository
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.identity.domain.OAuthAttempt
import dev.ostrom.server.identity.domain.OAuthAttemptsRepository
import dev.ostrom.server.identity.domain.RateLimiter
import dev.ostrom.server.identity.domain.SignInMethods
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.fakes.FakeOAuthProvider
import dev.ostrom.server.identity.fakes.RecordingMailSender
import dev.ostrom.server.identity.presentation.MagicLinkRequest
import dev.ostrom.server.identity.presentation.OAUTH_STATE_COOKIE
import dev.ostrom.server.identity.presentation.ProfileResponse
import dev.ostrom.server.identity.presentation.ProvidersResponse
import dev.ostrom.server.identity.presentation.SignedInResponse
import dev.ostrom.server.identity.presentation.VerifyMagicLinkRequest
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.koin.dsl.module

/** Every sign-in journey end to end, with fake providers and a recording mail sender in place of the real ones. */
class SignInRoutesPostgresTest {
    private val github = FakeOAuthProvider(IdentityProvider.GITHUB)
    // The Workspace of example.com vouches for its addresses (S2-07): signing in with it reaches Ada's account.
    private val google = FakeOAuthProvider(
        IdentityProvider.GOOGLE,
        VerifiedIdentity(IdentityProvider.GOOGLE, "g-1", "ada@example.com", true, "Ada G", hostedDomain = WORKSPACE),
    )
    private val mail = RecordingMailSender()

    private companion object {
        const val WORKSPACE = "example.com"
    }
    private val overrides = module {
        single {
            SignInMethods(mapOf(IdentityProvider.GITHUB to github, IdentityProvider.GOOGLE to google), email = true)
        }
        single<MailSender> { mail }
    }

    private fun HttpResponse.cookie(name: String) =
        headers.getAll(HttpHeaders.SetCookie).orEmpty().map(::parseServerSetCookieHeader).lastOrNull { it.name == name }

    @Test
    fun theLoginPageListsTheOfferedMethods() = serverTest(listOf(IdentityModule), overrides) { server ->
        assertEquals(
            listOf("github", "google", "email"),
            server.client.get("/api/v1/auth/providers").body<ProvidersResponse>().methods,
        )
    }

    @Test
    fun withoutConfigurationNothingIsOffered() = serverTest(listOf(IdentityModule)) { server ->
        assertEquals(emptyList(), server.client.get("/api/v1/auth/providers").body<ProvidersResponse>().methods)
        val browser = createClient { followRedirects = false }
        val start = browser.get("/api/v1/auth/github/start")
        assertEquals("http://localhost:5173/login?error=unavailable", start.headers[HttpHeaders.Location])
        val email = server.client.post("/api/v1/auth/email") {
            contentType(ContentType.Application.Json)
            setBody(MagicLinkRequest("ada@example.com"))
        }
        assertEquals(HttpStatusCode.NotFound, email.status)
    }

    @Test
    fun signingUpWithGitHubThenGoogleGivesOneAccount() = serverTest(listOf(IdentityModule), overrides) { server ->
        val browser = createClient {
            followRedirects = false
            install(ContentNegotiation) { json(OstromJson) }
        }

        val start = browser.get("/api/v1/auth/github/start?redirect=/repos")
        assertEquals(HttpStatusCode.Found, start.status)
        val consent = Url(assertNotNull(start.headers[HttpHeaders.Location]))
        val state = assertNotNull(consent.parameters["state"])
        assertEquals("http://localhost:8080/api/v1/auth/github/callback", consent.parameters["redirect"])
        val stateCookie = assertNotNull(start.cookie(OAUTH_STATE_COOKIE))
        assertEquals(state, stateCookie.value)
        assertEquals("/api/v1/auth", stateCookie.path)
        assertTrue(stateCookie.httpOnly)
        assertEquals(600, stateCookie.maxAge)

        val callback = browser.get("/api/v1/auth/github/callback?code=good&state=$state") {
            header(HttpHeaders.Cookie, "$OAUTH_STATE_COOKIE=$state")
        }
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("http://localhost:5173/repos", callback.headers[HttpHeaders.Location])
        assertEquals("", callback.cookie(OAUTH_STATE_COOKIE)?.value, "The state cookie is cleared")
        val session = assertNotNull(callback.cookie(SESSION_COOKIE)).value
        val profile = browser.get("/api/v1/auth/me") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=$session")
        }.body<ProfileResponse>()
        assertEquals("Ada", profile.displayName)

        // Google proves the same verified e-mail: same account, one more way in.
        val googleStart = browser.get("/api/v1/auth/google/start")
        val googleState =
            assertNotNull(Url(assertNotNull(googleStart.headers[HttpHeaders.Location])).parameters["state"])
        val googleCallback = browser.get("/api/v1/auth/google/callback?code=good&state=$googleState") {
            header(HttpHeaders.Cookie, "$OAUTH_STATE_COOKIE=$googleState")
        }
        assertEquals("http://localhost:5173/", googleCallback.headers[HttpHeaders.Location])
        val second = assertNotNull(googleCallback.cookie(SESSION_COOKIE)).value
        val merged = browser.get("/api/v1/auth/me") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=$second")
        }.body<ProfileResponse>()
        assertEquals(profile.id, merged.id)
        assertEquals(listOf("github", "google"), merged.providers)
    }

    @Test
    fun aStartPastTheRateLimitSendsBackToTheLoginPage() = serverTest(
        listOf(IdentityModule),
        module {
            includes(overrides)
            single<RateLimiter> {
                object : RateLimiter {
                    override suspend fun tryAcquire(key: String, limit: Int, window: Duration) = false
                }
            }
        },
    ) {
        val browser = createClient { followRedirects = false }
        val start = browser.get("/api/v1/auth/github/start")
        assertEquals("http://localhost:5173/login?error=rate_limited", start.headers[HttpHeaders.Location])
    }

    @Test
    fun aCallbackFromAnotherBrowserOrARefusalSignsNobodyIn() = serverTest(listOf(IdentityModule), overrides) { server ->
        val browser = createClient { followRedirects = false }
        val startLocation = assertNotNull(browser.get("/api/v1/auth/github/start").headers[HttpHeaders.Location])
        val state = Url(startLocation).parameters["state"]

        val noCookie = browser.get("/api/v1/auth/github/callback?code=good&state=$state")
        assertEquals("http://localhost:5173/login?error=expired", noCookie.headers[HttpHeaders.Location])
        assertNull(noCookie.cookie(SESSION_COOKIE))
        val denied = browser.get("/api/v1/auth/github/callback?error=access_denied&state=$state")
        assertEquals("http://localhost:5173/login?error=access_denied", denied.headers[HttpHeaders.Location])
        val badCode = browser.get("/api/v1/auth/github/callback?code=bad&state=$state") {
            header(HttpHeaders.Cookie, "$OAUTH_STATE_COOKIE=$state")
        }
        assertEquals("http://localhost:5173/login?error=refused", badCode.headers[HttpHeaders.Location])
        // The refused code consumed the attempt: the state cannot be replayed with a good code.
        val replay = browser.get("/api/v1/auth/github/callback?code=good&state=$state") {
            header(HttpHeaders.Cookie, "$OAUTH_STATE_COOKIE=$state")
        }
        assertEquals("http://localhost:5173/login?error=expired", replay.headers[HttpHeaders.Location])
        assertNull(replay.cookie(SESSION_COOKIE))
    }

    @Test
    fun aMagicLinkSignsUpOnceAndTheAnswerNeverTellsIfTheAccountExists() = serverTest(
        listOf(IdentityModule),
        overrides,
    ) { server ->
        suspend fun ask(email: String) = server.client.post("/api/v1/auth/email") {
            contentType(ContentType.Application.Json)
            setBody(MagicLinkRequest(email, "/welcome"))
        }

        val first = ask("Ada@Example.com")
        assertEquals(HttpStatusCode.Accepted, first.status)
        val link = mail.sent.single().text.lines().first { it.startsWith("http") }
        assertTrue(link.startsWith("http://localhost:5173/auth/email#token="), link)
        val token = link.substringAfter("#token=")

        val verified = server.client.post("/api/v1/auth/email/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyMagicLinkRequest(token))
        }
        assertEquals(HttpStatusCode.OK, verified.status)
        val signedIn = verified.body<SignedInResponse>()
        assertEquals("/welcome", signedIn.redirect)
        val session = assertNotNull(verified.cookie(SESSION_COOKIE)).value
        val profile = server.client.get("/api/v1/auth/me") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=$session")
        }.body<ProfileResponse>()
        assertEquals("ada@example.com", profile.primaryEmail)
        assertEquals(signedIn.csrfToken, profile.csrfToken)
        val logout = server.client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=$session")
            header(CSRF_HEADER, signedIn.csrfToken)
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)

        val reused = server.client.post("/api/v1/auth/email/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyMagicLinkRequest(token))
        }
        assertEquals(HttpStatusCode.BadRequest, reused.status)
        // Same answer for an address with an account and one without.
        assertEquals(HttpStatusCode.Accepted, ask("ada@example.com").status)
        assertEquals(HttpStatusCode.Accepted, ask("nobody@example.com").status)
        assertEquals(HttpStatusCode.BadRequest, ask("not-an-email").status)
    }

    @Test
    fun attemptsAndLinksAreUsableOnceAndNotAfterExpiry() = serverTest(listOf(IdentityModule)) { server ->
        val attempts = server.koin.get<OAuthAttemptsRepository>()
        val links = server.koin.get<MagicLinksRepository>()
        val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        val attempt =
            OAuthAttempt(Uuid.random(), IdentityProvider.OIDC, Uuid.random(), "v", "n", "/x", now, now + 10.minutes)
        attempts.create(attempt, "state-hash")
        val link = MagicLink(Uuid.random(), "ada@example.com", "/y", now, now + 15.minutes)
        links.create(link, "token-hash")

        assertNull(attempts.consume("state-hash", now + 11.minutes))
        assertNull(attempts.consume("other", now))
        assertEquals(attempt, attempts.consume("state-hash", now))
        assertNull(attempts.consume("state-hash", now))
        assertNull(links.consume("token-hash", now + 16.minutes))
        assertEquals(link, links.consume("token-hash", now))
        assertNull(links.consume("token-hash", now))
    }
}
