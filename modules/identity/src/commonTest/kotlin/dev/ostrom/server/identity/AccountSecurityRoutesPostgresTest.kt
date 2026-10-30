// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.ANSWER_SCOPE
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.GitHubAccount
import dev.ostrom.server.identity.domain.GitHubTokenVerifier
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.OAuthAttempt
import dev.ostrom.server.identity.domain.OAuthAttemptsRepository
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.SignInMethods
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.fakes.FakeOAuthProvider
import dev.ostrom.server.identity.infrastructure.GitHubTokenVerifierImpl
import dev.ostrom.server.identity.infrastructure.OAuthClient
import dev.ostrom.server.identity.presentation.LauncherLoginRequest
import dev.ostrom.server.identity.presentation.LauncherLoginResponse
import dev.ostrom.server.identity.presentation.LinkStartedResponse
import dev.ostrom.server.identity.presentation.OAUTH_STATE_COOKIE
import dev.ostrom.server.identity.presentation.ProfileResponse
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireScope
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
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
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.koin.core.Koin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A route that answers a gate, as the gates module will declare it: it asks for [ANSWER_SCOPE]. */
private object AnswerProbe : ServerModule {
    override val name = "answerprobe"
    override val koinModule = module { }

    override fun routes(route: Route, koin: Koin) {
        route.authenticated {
            post("/api/v1/test/gates/answer") {
                call.requireScope(ANSWER_SCOPE)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** Audit S2-04/S2-31, S2-07 and S2-22 end to end: real HTTP, real PostgreSQL, fake GitHub and providers. */
class AccountSecurityRoutesPostgresTest {
    private val gitHub = FakeGitHub()
    private val githubSignIn = FakeOAuthProvider(
        IdentityProvider.GITHUB, VerifiedIdentity(IdentityProvider.GITHUB, "42", "ada@example.com", true, "Ada"),
    )

    // Google for an address outside Gmail and outside a Workspace of its domain: no authority for it.
    private val google = FakeOAuthProvider(
        IdentityProvider.GOOGLE, VerifiedIdentity(IdentityProvider.GOOGLE, "g-1", "ada@example.com", true, "Mallory"),
    )
    private val overrides = module {
        single {
            val providers = mapOf(IdentityProvider.GITHUB to githubSignIn, IdentityProvider.GOOGLE to google)
            SignInMethods(providers, email = false)
        }
        single<GitHubTokenVerifier> {
            GitHubTokenVerifierImpl(OAuthClient("ostrom-app", "app-secret"), gitHub.http, "https://api.gh.test")
        }
    }

    private suspend fun TestServer.user(subject: String, email: String): Pair<User, StartedSession> {
        val identity = VerifiedIdentity(IdentityProvider.GITHUB, subject, email, true, subject)
        val user = koin.get<ResolveUserUseCase>()(identity)
        return user to koin.get<StartWebSessionUseCase>()(user.id)
    }

    private suspend fun TestServer.token(user: User, secret: String, kind: TokenKind, scopes: Set<String>) {
        val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        val expires = now + 1.days
        val hint = secret.take(8)
        val row = ApiToken(Uuid.random(), user.id, null, "t", kind, scopes, hint, null, now, expires, null, null)
        koin.get<ApiTokensRepository>().create(row, koin.get<SecretHasher>().hash(secret))
    }

    private fun HttpRequestBuilder.session(started: StartedSession) = browserSession(started.secret, started.csrfToken)

    private fun HttpResponse.cookie(name: String) =
        headers.getAll(HttpHeaders.SetCookie).orEmpty().map(::parseServerSetCookieHeader).lastOrNull { it.name == name }

    @Test
    fun noTokenAnswersAGateEvenWithTheScopeWrittenInTheDatabase() =
        serverTest(listOf(IdentityModule, AnswerProbe), overrides) { server ->
            val (ada, started) = server.user("1", "ada@example.com")
            // S2-04 / S2-31: rows as an old server, a bug or a hand edit could have left them.
            server.token(ada, "osc_cli-with-answer", TokenKind.CLI, setOf(ANSWER_SCOPE, "workflows:write", "user:*"))
            server.token(ada, "osp_personal-with-answer", TokenKind.PERSONAL, setOf(ANSWER_SCOPE, "user:*"))

            suspend fun answer(build: HttpRequestBuilder.() -> Unit) =
                server.client.post("/api/v1/test/gates/answer", build)
            assertEquals(HttpStatusCode.Forbidden, answer { bearerAuth("osc_cli-with-answer") }.status)
            assertEquals(HttpStatusCode.Forbidden, answer { bearerAuth("osp_personal-with-answer") }.status)
            // The person, in the console, still answers.
            assertEquals(HttpStatusCode.NoContent, answer { session(started) }.status)
        }

    @Test
    fun theLauncherLoginTakesOnlyATokenGitHubIssuedToOstrom() =
        serverTest(listOf(IdentityModule, AnswerProbe), overrides) { server ->
            val (ada, _) = server.user("583231", "ada@example.com")
            suspend fun login(token: String) = server.client.post("/api/v1/auth/launcher/login") {
                contentType(ContentType.Application.Json)
                setBody(LauncherLoginRequest(token, "ada-laptop"))
            }

            // S2-22: any string, another app's token or a personal access token is refused.
            assertEquals(HttpStatusCode.Unauthorized, login("ghp_forged").status)
            assertEquals(HttpStatusCode.Unauthorized, login("").status)
            gitHub.issued["gho_bob"] = GitHubAccount(7, "bob")
            assertEquals(HttpStatusCode.NotFound, login("gho_bob").status, "Bob has no Ostrom account")

            val response = login("gho_ostrom")
            assertEquals(HttpStatusCode.OK, response.status)
            val signedIn = response.body<LauncherLoginResponse>()
            assertEquals(ada.login, signedIn.login)
            val profile = server.client.get("/api/v1/auth/me") { bearerAuth(signedIn.token) }.body<ProfileResponse>()
            assertEquals(ada.id.toString(), profile.id)
            // The CLI token it gives cannot answer a gate either.
            val answer = server.client.post("/api/v1/test/gates/answer") { bearerAuth(signedIn.token) }
            assertEquals(HttpStatusCode.Forbidden, answer.status)
        }

    @Test
    fun aProviderNamingTheAddressOfAnotherAccountSignsNobodyInAndLinksOnlyFromThatAccount() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val browser = createClient { followRedirects = false }
            val (ada, adaSession) = server.user("42", "ada@example.com")

            // S2-07: Google says "ada@example.com" without being its authority: no session, nobody merged.
            val start = browser.get("/api/v1/auth/google/start")
            val state = assertNotNull(Url(assertNotNull(start.headers[HttpHeaders.Location])).parameters["state"])
            val takeover = browser.get("/api/v1/auth/google/callback?code=good&state=$state") {
                header(HttpHeaders.Cookie, "$OAUTH_STATE_COOKIE=$state")
            }
            assertEquals("http://localhost:5173/login?error=link_required", takeover.headers[HttpHeaders.Location])
            assertNull(takeover.cookie(SESSION_COOKIE))

            // A token never starts a link; Ada's browser session does.
            server.token(ada, "osp_personal", TokenKind.PERSONAL, setOf("profile:read"))
            val byToken = browser.post("/api/v1/auth/google/link") { bearerAuth("osp_personal") }
            assertEquals(HttpStatusCode.Forbidden, byToken.status)
            val link = server.client.post("/api/v1/auth/google/link?redirect=/settings") { session(adaSession) }
            assertEquals(HttpStatusCode.OK, link.status)
            val linkState = Url(link.body<LinkStartedResponse>().authorizationUrl).parameters["state"]
            assertEquals(linkState, link.cookie(OAUTH_STATE_COOKIE)?.value)
            val linked = browser.get("/api/v1/auth/google/callback?code=good&state=$linkState") {
                header(HttpHeaders.Cookie, "$OAUTH_STATE_COOKIE=$linkState")
            }
            assertEquals("http://localhost:5173/settings", linked.headers[HttpHeaders.Location])
            val profile = server.client.get("/api/v1/auth/me") { session(adaSession) }.body<ProfileResponse>()
            assertEquals(ada.id.toString(), profile.id)
            assertEquals(listOf("github", "google"), profile.providers)

            // Bob, signed in, cannot take Ada's Google for himself.
            val (_, bobSession) = server.user("7", "bob@example.com")
            val bobLink = server.client.post("/api/v1/auth/google/link") { session(bobSession) }
            val bobState = Url(bobLink.body<LinkStartedResponse>().authorizationUrl).parameters["state"]
            val taken = browser.get("/api/v1/auth/google/callback?code=good&state=$bobState") {
                header(HttpHeaders.Cookie, "$OAUTH_STATE_COOKIE=$bobState")
            }
            assertEquals("http://localhost:5173/login?error=identity_taken", taken.headers[HttpHeaders.Location])
            assertNull(taken.cookie(SESSION_COOKIE))
        }

    @Test
    fun anAttemptKeepsTheAccountItLinksTo() = serverTest(listOf(IdentityModule)) { server ->
        val (ada, _) = server.user("1", "ada@example.com")
        val attempts = server.koin.get<OAuthAttemptsRepository>()
        val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        val expires = now + 10.minutes
        val attempt = OAuthAttempt(Uuid.random(), IdentityProvider.GOOGLE, null, "v", "n", "/x", now, expires, ada.id)

        attempts.create(attempt, "link-state")

        assertEquals(attempt, attempts.consume("link-state", now))
        assertNull(attempts.consume("link-state", now))
    }
}
