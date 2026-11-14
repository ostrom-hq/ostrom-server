// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.CLI_TOKEN_LIFETIME
import run.ostrom.server.identity.domain.GitHubAccount
import run.ostrom.server.identity.domain.GitHubTokenRejectedException
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.IssueCliTokenUseCaseImpl
import run.ostrom.server.identity.domain.LauncherAccountNotFoundException
import run.ostrom.server.identity.domain.LauncherLoginUseCaseImpl
import run.ostrom.server.identity.domain.ProviderNotConfiguredException
import run.ostrom.server.identity.domain.ProviderRejectedException
import run.ostrom.server.identity.domain.RateLimitedException
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.fakes.CountingSecretGenerator
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import run.ostrom.server.identity.fakes.InMemoryUsersRepository
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.fakes.ScriptedRateLimiter
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.infrastructure.GitHubSignIn
import run.ostrom.server.identity.infrastructure.GitHubTokenVerifierImpl
import run.ostrom.server.identity.infrastructure.OAuthClient
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
import kotlin.time.Duration.Companion.seconds
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

/** GitHub's sign-in of the fake GitHub, with a client. */
private fun signIn(client: OAuthClient) = GitHubSignIn(client, "https://gh.test", "https://api.gh.test")

class LauncherLoginTest {
    private val github = FakeGitHub()
    private val app = OAuthClient("ostrom-app", "app-secret")
    private val verifier = GitHubTokenVerifierImpl({ listOf(signIn(app)) }, github.http)
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
    fun aBlankLabelGetsTheDefaultOneAndTheFiftyFirstWorkstationSignsTheOldestOut() = runTest {
        val ada = ada()

        val oldest = login("gho_ostrom", " ", "ip").token.token
        assertEquals("ostrom launcher", oldest.name)
        clock.now += 1.seconds
        assertEquals("ostrom launcher", login("gho_ostrom", null, "ip").token.token.name)
        repeat(48) {
            clock.now += 1.seconds
            issue(ada.id, "t$it")
        }
        clock.now += 1.seconds
        val latest = login("gho_ostrom", null, "ip").token.token

        val live = tokens.listLive(ada.id, setOf(TokenKind.CLI), clock.now)
        assertEquals(50, live.size, "Fifty workstations at most, the new one never refused")
        assertEquals(latest, live.first())
        assertNull(live.firstOrNull { it.id == oldest.id }, "The oldest login is signed out")
        val revoked = audit.entries.single { it.action == "token.revoked" }
        assertEquals(mapOf("kind" to "cli", "reason" to "device_limit"), revoked.details)
    }

    @Test
    fun gitHubDownOrAnInstanceWithoutGitHubFailsClosed() = runTest {
        github.status = HttpStatusCode.InternalServerError
        assertFailsWith<ProviderRejectedException> { verifier.verify("gho_ostrom") }
        github.status = HttpStatusCode.UnprocessableEntity
        assertFailsWith<ProviderRejectedException> { verifier.verify("gho_ostrom") }
        github.status = null
        val wrong = OAuthClient("ostrom-app", "wrong")
        val wrongSecret = GitHubTokenVerifierImpl({ listOf(signIn(wrong)) }, github.http)
        assertNull(wrongSecret.verify("gho_ostrom"))
        val unconfigured = GitHubTokenVerifierImpl({ emptyList() }, github.http)
        assertFailsWith<ProviderNotConfiguredException> { unconfigured.verify("gho_ostrom") }
        // A client that does not know the token first, then the one that issued it: the latter's answer counts.
        val both = GitHubTokenVerifierImpl({ listOf(signIn(OAuthClient("Iv1.app", "s")), signIn(app)) }, github.http)
        assertEquals("583231", both.verify("gho_ostrom")?.id?.toString())
        val unreachable = HttpClient(MockEngine { error("network down") }) { expectSuccess = false }
        assertFailsWith<ProviderRejectedException> {
            GitHubTokenVerifierImpl({ listOf(signIn(app)) }, unreachable).verify("gho_ostrom")
        }
    }

    @Test
    fun theVerifierNamesTheAccountGitHubReports() = runTest {
        assertEquals(GitHubAccount(583231, "ada"), verifier.verify("gho_ostrom"))
        val body = (github.requests.single().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals("""{"access_token":"gho_ostrom"}""", body)
    }
}
