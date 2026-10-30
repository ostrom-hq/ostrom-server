// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.CLI_TOKEN_LIFETIME
import dev.ostrom.server.identity.domain.GitHubAccount
import dev.ostrom.server.identity.domain.GitHubTokenRejectedException
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.IssueCliTokenUseCaseImpl
import dev.ostrom.server.identity.domain.LauncherAccountNotFoundException
import dev.ostrom.server.identity.domain.LauncherLoginUseCaseImpl
import dev.ostrom.server.identity.domain.MAX_TOKENS_PER_USER
import dev.ostrom.server.identity.domain.ProviderNotConfiguredException
import dev.ostrom.server.identity.domain.ProviderRejectedException
import dev.ostrom.server.identity.domain.RateLimitedException
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.TooManyTokensException
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.fakes.CountingSecretGenerator
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import dev.ostrom.server.identity.fakes.InMemoryUsersRepository
import dev.ostrom.server.identity.fakes.RecordingAuditLog
import dev.ostrom.server.identity.fakes.ScriptedRateLimiter
import dev.ostrom.server.identity.fakes.SettableClock
import dev.ostrom.server.identity.infrastructure.GitHubTokenVerifierImpl
import dev.ostrom.server.identity.infrastructure.OAuthClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** A fake GitHub: it knows the tokens it issued to Ostrom's app, and answers 404 for any other (S2-22). */
class FakeGitHub(private val clientId: String = "ostrom-app", private val clientSecret: String = "app-secret") {
    /** The tokens GitHub issued to Ostrom's app, and the account of each. */
    val issued = mutableMapOf("gho_ostrom" to GitHubAccount(583231, "ada"))
    val requests = mutableListOf<HttpRequestData>()
    var status: HttpStatusCode? = null

    val http = HttpClient(
        MockEngine { request ->
            requests += request
            val forced = status
            val basic = "Basic " + Base64.encode("$clientId:$clientSecret".encodeToByteArray())
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val token = Regex("\"access_token\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)
            val account = issued[token]
            when {
                forced != null -> respond("{}", forced, json)
                request.method != HttpMethod.Post || request.url.encodedPath != "/applications/$clientId/token" ->
                    respond("{}", HttpStatusCode.NotFound, json)
                request.headers[HttpHeaders.Authorization] != basic -> respond("{}", HttpStatusCode.NotFound, json)
                account == null -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, json)
                else -> respond(
                    """{"id":1,"token":"$token","app":{"client_id":"$clientId"},""" +
                        """"user":{"id":${account.id},"login":"${account.login}","type":"User"}}""",
                    HttpStatusCode.OK,
                    json,
                )
            }
        },
    ) { expectSuccess = false }

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
}

class LauncherLoginTest {
    private val github = FakeGitHub()
    private val app = OAuthClient("ostrom-app", "app-secret")
    private val verifier = GitHubTokenVerifierImpl(app, github.http, "https://api.gh.test")
    private val users = InMemoryUsersRepository()
    private val tokens = InMemoryApiTokensRepository()
    private val limiter = ScriptedRateLimiter()
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val issue = IssueCliTokenUseCaseImpl(tokens, CountingSecretGenerator(), FakeHasher(), audit, clock)
    private val login = LauncherLoginUseCaseImpl(verifier, users, limiter, issue)

    private suspend fun ada(): User {
        val ada = User(Uuid.random(), "ada", "Ada", null, null, clock.now)
        users.create(ada, VerifiedIdentity(IdentityProvider.GITHUB, "583231", null, false), null, clock.now)
        return ada
    }

    @Test
    fun aTokenGitHubIssuedToOstromGivesTheUserACliToken() = runTest {
        val ada = ada()

        val signedIn = login("gho_ostrom", "  ada-laptop ", "1.2.3.4")

        assertEquals(ada, signedIn.user)
        val token = signedIn.token.token
        assertEquals(TokenKind.CLI, token.kind)
        assertEquals(CLI_SCOPES, token.scopes)
        assertEquals("ada-laptop", token.name)
        assertEquals(clock.now + CLI_TOKEN_LIFETIME, token.expiresAt)
        assertTrue(signedIn.token.secret.startsWith("osc_"))
        assertEquals(token, tokens.tokens.getValue("h(${signedIn.token.secret})"))
        assertEquals(listOf("launcher-login:1.2.3.4"), limiter.keys)
        assertEquals(mapOf("kind" to "cli", "via" to "launcher"), audit.entries.single().details)
        // GitHub was asked with the app's credentials, the GitHub token in the body only.
        val request = github.requests.single()
        assertEquals("https://api.gh.test/applications/ostrom-app/token", request.url.toString())
        assertEquals("application/vnd.github+json", request.headers[HttpHeaders.Accept])
    }

    @Test
    fun anyOtherGitHubTokenIsRefused() = runTest {
        ada()

        // A token of another OAuth app, a personal access token or a revoked one: GitHub does not know it for Ostrom.
        assertFailsWith<GitHubTokenRejectedException> { login("gho_other_app", null, "ip") }
        assertFailsWith<GitHubTokenRejectedException> { login("ghp_personal", null, "ip") }
        // Nothing is even asked for an empty or oversized value.
        assertFailsWith<GitHubTokenRejectedException> { login("  ", null, "ip") }
        assertFailsWith<GitHubTokenRejectedException> { login("g".repeat(256), null, "ip") }
        assertEquals(2, github.requests.size)
        assertTrue(tokens.tokens.isEmpty())
    }

    @Test
    fun theGitHubAccountMustSignInToOstrom() = runTest {
        assertFailsWith<LauncherAccountNotFoundException> { login("gho_ostrom", null, "ip") }
        assertTrue(tokens.tokens.isEmpty())
    }

    @Test
    fun theLoginIsRateLimitedPerAddressBeforeGitHubIsAsked() = runTest {
        limiter.allow = false

        assertFailsWith<RateLimitedException> { login("gho_ostrom", null, "ip") }
        assertTrue(github.requests.isEmpty())
    }

    @Test
    fun aBlankLabelGetsTheDefaultOneAndTheTokenCountIsCapped() = runTest {
        val ada = ada()

        assertEquals("ostrom launcher", login("gho_ostrom", " ", "ip").token.token.name)
        assertEquals("ostrom launcher", login("gho_ostrom", null, "ip").token.token.name)
        repeat(MAX_TOKENS_PER_USER - 2) { issue(ada.id, "t$it") }
        assertFailsWith<TooManyTokensException> { login("gho_ostrom", null, "ip") }
    }

    @Test
    fun gitHubDownOrAnInstanceWithoutGitHubFailsClosed() = runTest {
        github.status = HttpStatusCode.InternalServerError
        assertFailsWith<ProviderRejectedException> { verifier.verify("gho_ostrom") }
        github.status = HttpStatusCode.UnprocessableEntity
        assertFailsWith<ProviderRejectedException> { verifier.verify("gho_ostrom") }
        github.status = null
        val wrong = OAuthClient("ostrom-app", "wrong")
        val wrongSecret = GitHubTokenVerifierImpl(wrong, github.http, "https://api.gh.test")
        assertNull(wrongSecret.verify("gho_ostrom"))
        val unconfigured = GitHubTokenVerifierImpl(null, github.http)
        assertFailsWith<ProviderNotConfiguredException> { unconfigured.verify("gho_ostrom") }
        val unreachable = HttpClient(MockEngine { error("network down") }) { expectSuccess = false }
        assertFailsWith<ProviderRejectedException> {
            GitHubTokenVerifierImpl(app, unreachable).verify("gho_ostrom")
        }
    }

    @Test
    fun theVerifierNamesTheAccountGitHubReports() = runTest {
        assertEquals(GitHubAccount(583231, "ada"), verifier.verify("gho_ostrom"))
        val body = (github.requests.single().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals("""{"access_token":"gho_ostrom"}""", body)
    }
}
