// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.testing.TestInstanceSettings
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.identity.fakes.offered
import run.ostrom.server.identity.domain.BootstrapInstanceUseCase
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.SetupLinksRepository
import run.ostrom.server.identity.domain.SignInMethods
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.identity.fakes.FakeOAuthProvider
import run.ostrom.server.identity.fakes.RecordingMailSender
import run.ostrom.server.identity.presentation.AccountResponse
import run.ostrom.server.identity.presentation.EMAIL_LINK_COOKIE
import run.ostrom.server.identity.presentation.MagicLinkRequest
import run.ostrom.server.identity.presentation.OAUTH_STATE_COOKIE
import run.ostrom.server.identity.presentation.ProvidersResponse
import run.ostrom.server.identity.presentation.SIGN_UP_COOKIE
import run.ostrom.server.identity.presentation.SignUpGrantRequest
import run.ostrom.server.identity.presentation.VerifyMagicLinkRequest
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.kernel.module.InstanceBootstrapper
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.koin.dsl.module

/** TC-CODE-AUTH-2 / TC-CODE-SETUP-6: an invite-only instance end to end, from its setup link to a refused stranger. */
class SignUpRoutesPostgresTest {
    private val github = FakeOAuthProvider(IdentityProvider.GITHUB)
    private val mail = RecordingMailSender()
    private val inviteOnly = module {
        single { SignInMethods(mapOf(IdentityProvider.GITHUB to github), email = true).offered() }
        single<MailSender> { mail }
        single<InstanceSettings> { TestInstanceSettings(mapOf("sign-up" to mapOf("mode" to "invite"))) }
    }

    private fun HttpResponse.cookie(name: String) =
        headers.getAll(HttpHeaders.SetCookie).orEmpty().map(::parseServerSetCookieHeader).lastOrNull { it.name == name }

    /** Signs in with the fake GitHub in a browser holding [cookies], up to the callback's answer. */
    private suspend fun HttpClient.gitHubCallback(cookies: String = ""): HttpResponse {
        val start = get("/api/v1/auth/github/start")
        val state = assertNotNull(Url(assertNotNull(start.headers[HttpHeaders.Location])).parameters["state"])
        return get("/api/v1/auth/github/callback?code=good&state=$state") {
            header(HttpHeaders.Cookie, "$OAUTH_STATE_COOKIE=$state$cookies")
        }
    }

    private suspend fun HttpClient.grant(token: String, origin: String? = "http://localhost:5173") =
        post("/api/v1/auth/signup-grant") {
            origin?.let { header(HttpHeaders.Origin, it) }
            contentType(ContentType.Application.Json)
            setBody(SignUpGrantRequest(token))
        }

    @Test
    fun aStrangerIsRefusedThenTheSetupLinksPersonSignsUpOnce() = serverTest(listOf(IdentityModule), inviteOnly) {
        server ->
        val browser = createClient { followRedirects = false }
        assertEquals("invite", server.client.get("/api/v1/auth/providers").body<ProvidersResponse>().signUp)

        val refused = browser.gitHubCallback()
        assertEquals("http://localhost:5173/login?error=signup_closed", refused.headers[HttpHeaders.Location])
        assertNull(refused.cookie(SESSION_COOKIE), "No session for a stranger")

        val link = assertNotNull(server.koin.get<InstanceBootstrapper>().setupLink())
        assertTrue(link.url.startsWith("http://localhost:5173/setup#token=osb_"), link.url)
        val token = link.url.substringAfter("#token=")
        assertEquals(HttpStatusCode.Forbidden, server.client.grant(token, origin = null).status, "The console only")
        assertEquals(HttpStatusCode.NotFound, server.client.grant("osb_unknown").status)
        val granted = server.client.grant(token)
        assertEquals(HttpStatusCode.NoContent, granted.status)
        val cookie = assertNotNull(granted.cookie(SIGN_UP_COOKIE))
        assertEquals(token, cookie.value)
        assertEquals("/api/v1/auth", cookie.path)
        assertEquals(3600, cookie.maxAge)
        assertTrue(cookie.httpOnly)

        val signedUp = browser.gitHubCallback("; $SIGN_UP_COOKIE=$token")
        assertEquals("http://localhost:5173/", signedUp.headers[HttpHeaders.Location])
        assertEquals("", signedUp.cookie(SIGN_UP_COOKIE)?.value, "The grant served its purpose")
        val session = assertNotNull(signedUp.cookie(SESSION_COOKIE)).value
        val ada = server.client.get("/api/v1/auth/me/account") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=$session")
        }.body<AccountResponse>()
        assertEquals("Ada", ada.displayName)
        val users = server.koin.get<UsersRepository>()
        val adaId = assertNotNull(users.findByIdentity(IdentityProvider.GITHUB.key, "42")).id
        val links = server.koin.get<SetupLinksRepository>()
        assertTrue(links.usedBy(adaId), "Ada may create the organizations")
        assertTrue(links.anyAccount())

        assertEquals(HttpStatusCode.NotFound, server.client.grant(token).status, "The setup link works once")
        assertNull(server.koin.get<BootstrapInstanceUseCase>()(), "Owners invite people now")
        val again = browser.gitHubCallback()
        assertNotNull(again.cookie(SESSION_COOKIE), "Ada signs in again without any grant")
    }

    @Test
    fun anEmailedLinkCreatesNoAccountOnAnInviteOnlyInstance() = serverTest(listOf(IdentityModule), inviteOnly) {
        server ->
        val asked = server.client.post("/api/v1/auth/email") {
            contentType(ContentType.Application.Json)
            setBody(MagicLinkRequest("bob@example.com"))
        }
        val nonce = assertNotNull(asked.cookie(EMAIL_LINK_COOKIE)).value
        val token = mail.sent.single().text.lines().first { it.startsWith("http") }.substringAfter("#token=")

        val verified = server.client.post("/api/v1/auth/email/verify") {
            header(HttpHeaders.Cookie, "$EMAIL_LINK_COOKIE=$nonce")
            contentType(ContentType.Application.Json)
            setBody(VerifyMagicLinkRequest(token))
        }

        assertEquals(HttpStatusCode.Forbidden, verified.status)
        assertEquals("signup_closed", verified.body<ApiError>().error.type)
        assertNull(verified.cookie(SESSION_COOKIE))
    }

    @Test
    fun theSetupLinksAreStoredHashedAndUsedOnce() = serverTest(listOf(IdentityModule), inviteOnly) { server ->
        val links = server.koin.get<SetupLinksRepository>()
        assertFalse(links.anyAccount(), "A new instance has no account")
        val first = assertNotNull(server.koin.get<BootstrapInstanceUseCase>()())
        val second = assertNotNull(server.koin.get<BootstrapInstanceUseCase>()())
        val now = second.link.createdAt
        assertFalse(links.pending("h-unknown", now))
        val hasher = server.koin.get<SecretHasher>()
        assertFalse(links.pending(hasher.hash(first.token), now), "A new link stops the earlier one")
        assertTrue(links.pending(hasher.hash(second.token), now))
        val ada = Uuid.random()
        assertTrue(links.consume(hasher.hash(second.token), ada, now))
        assertFalse(links.consume(hasher.hash(second.token), ada, now), "Once")
        assertTrue(links.usedBy(ada))
        assertFalse(links.usedBy(Uuid.random()))
    }
}
