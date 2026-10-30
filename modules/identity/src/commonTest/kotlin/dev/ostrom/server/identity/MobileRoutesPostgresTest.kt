// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.APP_REDIRECT_URI
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.SignInMethods
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.fakes.FakeOAuthProvider
import dev.ostrom.server.identity.infrastructure.Sha256SecretHasher
import dev.ostrom.server.identity.presentation.MOBILE_OAUTH_STATE_COOKIE
import dev.ostrom.server.identity.presentation.MobileTokenRequest
import dev.ostrom.server.identity.presentation.MobileTokenResponse
import dev.ostrom.server.identity.presentation.MobileUnlockRequest
import dev.ostrom.server.identity.presentation.MobileUnlockResponse
import dev.ostrom.server.identity.presentation.NotificationPreferencesBody
import dev.ostrom.server.identity.presentation.OAUTH_STATE_COOKIE
import dev.ostrom.server.identity.presentation.ProfileResponse
import dev.ostrom.server.identity.presentation.QuietHoursBody
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.koin.dsl.module

/**
 * The phone app's journey end to end (S2-24, S2-54): PKCE start with the app's deep link, callback to the app, code
 * exchange, biometric unlock, notification preferences; and the attacks on it (replayed code, wrong verifier, foreign
 * deep link).
 */
class MobileRoutesPostgresTest {
    private val github = FakeOAuthProvider(IdentityProvider.GITHUB)
    private val overrides = module {
        single { SignInMethods(mapOf(IdentityProvider.GITHUB to github), email = false) }
    }
    private val verifier = "app-verifier-" + "x".repeat(50)

    private fun HttpResponse.cookie(name: String) =
        headers.getAll(HttpHeaders.SetCookie).orEmpty().map(::parseServerSetCookieHeader).lastOrNull { it.name == name }

    private suspend fun challenge(of: String = verifier) = Sha256SecretHasher().pkceChallenge(of)

    private suspend fun HttpClient.startMobile(
        redirectUri: String = APP_REDIRECT_URI,
        method: String = "S256",
    ): HttpResponse {
        val challenge = challenge()
        return get("/api/v1/auth/github/start") {
            url.parameters.append("redirect_uri", redirectUri)
            url.parameters.append("state", "app-1")
            url.parameters.append("code_challenge", challenge)
            url.parameters.append("code_challenge_method", method)
        }
    }

    /** The deep link the in-app browser lands on, as the app reads it. */
    private suspend fun HttpClient.signInOnThePhone(error: String? = null): Url {
        val start = startMobile()
        assertEquals(HttpStatusCode.Found, start.status)
        val state = assertNotNull(Url(assertNotNull(start.headers[HttpHeaders.Location])).parameters["state"])
        val query = error?.let { "error=$it" } ?: "code=good"
        val callback = get("/api/v1/auth/github/callback?$query&state=$state") {
            header(HttpHeaders.Cookie, "$MOBILE_OAUTH_STATE_COOKIE=$state")
        }
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("", callback.cookie(MOBILE_OAUTH_STATE_COOKIE)?.value, "The mobile state cookie is cleared")
        assertNull(callback.cookie(SESSION_COOKIE), "No browser session in the in-app browser")
        return Url(assertNotNull(callback.headers[HttpHeaders.Location]))
    }

    private suspend fun HttpClient.exchange(
        code: String,
        codeVerifier: String = verifier,
        uri: String = APP_REDIRECT_URI,
    ) = post("/api/v1/auth/mobile/token") {
        contentType(ContentType.Application.Json)
        setBody(MobileTokenRequest(code, codeVerifier, uri))
    }

    private suspend fun HttpClient.preferences(token: String) =
        get("/api/v1/me/notifications") { bearerAuth(token) }.body<NotificationPreferencesBody>()

    private fun ApplicationTestBuilder.browser() = createClient {
        followRedirects = false
        install(ContentNegotiation) { json(OstromJson) }
    }

    @Test
    fun thePhoneSignsInWithPkceAndSaysYesOnlyAfterItsUnlock() = serverTest(listOf(IdentityModule), overrides) {
        val phone = browser()

        val start = phone.startMobile()
        val consent = Url(assertNotNull(start.headers[HttpHeaders.Location]))
        assertEquals("true", consent.parameters["reauth"], "A new phone is never signed in silently")
        val mobileCookie = assertNotNull(start.cookie(MOBILE_OAUTH_STATE_COOKIE))
        assertEquals(consent.parameters["state"], mobileCookie.value)
        assertTrue(mobileCookie.httpOnly)
        assertNull(start.cookie(OAUTH_STATE_COOKIE), "The browser's cookie is left alone")

        val link = phone.signInOnThePhone()
        assertEquals("ostrom", link.protocol.name)
        assertEquals("app-1", link.parameters["state"])
        val code = assertNotNull(link.parameters["code"])

        val exchanged = phone.exchange(code)
        assertEquals(HttpStatusCode.OK, exchanged.status)
        val token = exchanged.body<MobileTokenResponse>()
        assertTrue(token.token.startsWith("osm_"))
        assertEquals("Ada", token.user.name)
        assertNotNull(token.expiresAt)

        val me = phone.get("/api/v1/auth/me") { bearerAuth(token.token) }
        assertEquals("Ada", me.body<ProfileResponse>().displayName)

        val refused = phone.post("/api/v1/auth/mobile/unlock") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(MobileUnlockRequest("not-the-key"))
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        val unlocked = phone.post("/api/v1/auth/mobile/unlock") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(MobileUnlockRequest(token.unlockKey))
        }
        assertEquals(HttpStatusCode.OK, unlocked.status)
        assertNotNull(Instant.parse(unlocked.body<MobileUnlockResponse>().authenticatedAt))

        // Signing out on the phone revokes its token.
        assertEquals(HttpStatusCode.NoContent, phone.post("/api/v1/auth/logout") { bearerAuth(token.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, phone.get("/api/v1/auth/me") { bearerAuth(token.token) }.status)
    }

    @Test
    fun aCodeServesOnceAndOnlyWithItsVerifierAndItsDeepLink() = serverTest(listOf(IdentityModule), overrides) {
        val phone = browser()

        val replayed = assertNotNull(phone.signInOnThePhone().parameters["code"])
        assertEquals(HttpStatusCode.OK, phone.exchange(replayed).status)
        assertEquals(HttpStatusCode.BadRequest, phone.exchange(replayed).status, "Replayed code")

        val stolen = assertNotNull(phone.signInOnThePhone().parameters["code"])
        assertEquals(HttpStatusCode.BadRequest, phone.exchange(stolen, "y".repeat(64)).status, "Wrong verifier")
        assertEquals(HttpStatusCode.BadRequest, phone.exchange(stolen).status, "The wrong verifier spent it")

        val foreign = assertNotNull(phone.signInOnThePhone().parameters["code"])
        assertEquals(HttpStatusCode.BadRequest, phone.exchange(foreign, uri = "https://evil.example/cb").status)
        assertEquals(HttpStatusCode.BadRequest, phone.exchange("never-issued").status)
    }

    @Test
    fun aForeignDeepLinkOrAWeakMethodIsRefusedBeforeTheProvider() = serverTest(listOf(IdentityModule), overrides) {
        val phone = browser()

        val foreign = phone.startMobile(redirectUri = "https://evil.example/cb")
        assertEquals(HttpStatusCode.BadRequest, foreign.status)
        assertNull(foreign.headers[HttpHeaders.Location], "Nothing is redirected to a deep link that is not the app's")
        assertEquals(HttpStatusCode.BadRequest, phone.startMobile(method = "plain").status)
        assertTrue(github.exchanges.isEmpty())
    }

    @Test
    fun aRefusalAndAStaleCallbackReachTheRightPlace() = serverTest(listOf(IdentityModule), overrides) {
        val phone = browser()

        val denied = phone.signInOnThePhone(error = "access_denied")
        assertEquals("access_denied", denied.parameters["error"])
        assertEquals("app-1", denied.parameters["state"])

        // The same state again: the attempt is spent, the in-app browser still closes on the app's deep link.
        val start = phone.startMobile()
        val state = Url(assertNotNull(start.headers[HttpHeaders.Location])).parameters["state"]
        phone.get("/api/v1/auth/github/callback?code=good&state=$state") {
            header(HttpHeaders.Cookie, "$MOBILE_OAUTH_STATE_COOKIE=$state")
        }
        val replay = phone.get("/api/v1/auth/github/callback?code=good&state=$state") {
            header(HttpHeaders.Cookie, "$MOBILE_OAUTH_STATE_COOKIE=$state")
        }
        assertEquals("ostrom://auth/callback?error=expired", replay.headers[HttpHeaders.Location])

        // A mobile cookie of another sign-in does not turn a browser callback into a mobile one.
        val other = phone.get("/api/v1/auth/github/callback?code=good&state=$state") {
            header(HttpHeaders.Cookie, "$MOBILE_OAUTH_STATE_COOKIE=other")
        }
        assertEquals("http://localhost:5173/login?error=expired", other.headers[HttpHeaders.Location])
        val noState = phone.get("/api/v1/auth/github/callback?code=good") {
            header(HttpHeaders.Cookie, "$MOBILE_OAUTH_STATE_COOKIE=other")
        }
        assertEquals("http://localhost:5173/login?error=expired", noState.headers[HttpHeaders.Location])
    }

    @Test
    fun onlyTheAppTokenUnlocksAndNobodyWithoutACredential() = serverTest(listOf(IdentityModule), overrides) {
        val console = browser()
        val start = console.get("/api/v1/auth/github/start")
        val state = Url(assertNotNull(start.headers[HttpHeaders.Location])).parameters["state"]
        val callback = console.get("/api/v1/auth/github/callback?code=good&state=$state") {
            header(HttpHeaders.Cookie, "$OAUTH_STATE_COOKIE=$state")
        }
        val session = assertNotNull(callback.cookie(SESSION_COOKIE)).value
        val csrf = assertNotNull(callback.cookie(CSRF_COOKIE)).value

        val fromTheConsole = console.post("/api/v1/auth/mobile/unlock") {
            browserSession(session, csrf)
            contentType(ContentType.Application.Json)
            setBody(MobileUnlockRequest("anything"))
        }
        assertEquals(HttpStatusCode.Forbidden, fromTheConsole.status)
        val anonymous = console.post("/api/v1/auth/mobile/unlock") {
            contentType(ContentType.Application.Json)
            setBody(MobileUnlockRequest("anything"))
        }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals(HttpStatusCode.Unauthorized, console.get("/api/v1/me/notifications").status)
        val write = console.put("/api/v1/me/notifications") {
            contentType(ContentType.Application.Json)
            setBody(NotificationPreferencesBody(requests = false, decisions = false, spending = false))
        }
        assertEquals(HttpStatusCode.Unauthorized, write.status)

        // The console reads and writes the same preferences, with its session.
        val chosen = NotificationPreferencesBody(requests = true, decisions = false, spending = true)
        val fromConsole = console.put("/api/v1/me/notifications") {
            browserSession(session, csrf)
            contentType(ContentType.Application.Json)
            setBody(chosen)
        }
        assertEquals(chosen, fromConsole.body<NotificationPreferencesBody>())
        val read = console.get("/api/v1/me/notifications") { browserSession(session, null) }
        assertEquals(chosen, read.body<NotificationPreferencesBody>())
    }

    @Test
    fun notificationPreferencesArePerPersonAndChecked() = serverTest(listOf(IdentityModule), overrides) {
        val phone = browser()
        val ada = phone.exchange(assertNotNull(phone.signInOnThePhone().parameters["code"]))
            .body<MobileTokenResponse>().token
        github.identity = VerifiedIdentity(IdentityProvider.GITHUB, "7", "grace@example.com", true, "Grace")
        val grace = phone.exchange(assertNotNull(phone.signInOnThePhone().parameters["code"]))
            .body<MobileTokenResponse>().token

        val defaults = NotificationPreferencesBody(requests = true, decisions = true, spending = false)
        assertEquals(defaults, phone.preferences(ada))

        val chosen = NotificationPreferencesBody(false, true, true, QuietHoursBody("22:00", "07:00", "Europe/Paris"))
        val put = phone.put("/api/v1/me/notifications") {
            bearerAuth(ada)
            contentType(ContentType.Application.Json)
            setBody(chosen)
        }
        assertEquals(HttpStatusCode.OK, put.status)
        assertEquals(chosen, put.body<NotificationPreferencesBody>())
        assertEquals(chosen, phone.preferences(ada))
        assertEquals(
            defaults,
            phone.preferences(grace),
            "Another person does not see them",
        )

        val invalid = phone.put("/api/v1/me/notifications") {
            bearerAuth(ada)
            contentType(ContentType.Application.Json)
            setBody(chosen.copy(quietHours = QuietHoursBody("22:00", "22:00", "UTC")))
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(chosen, phone.preferences(ada))
    }
}
