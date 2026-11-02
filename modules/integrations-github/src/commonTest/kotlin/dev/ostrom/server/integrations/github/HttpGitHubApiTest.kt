// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.integrations.github.domain.CheckRun
import dev.ostrom.server.integrations.github.domain.GitHubCallException
import dev.ostrom.server.integrations.github.domain.GitHubCiRun
import dev.ostrom.server.integrations.github.domain.GitHubPull
import dev.ostrom.server.integrations.github.infrastructure.AppJwt
import dev.ostrom.server.integrations.github.infrastructure.AppOAuthClient
import dev.ostrom.server.integrations.github.infrastructure.HttpGitHubApi
import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class HttpGitHubApiTest {
    private val clock = SettableClock()
    private val calls = mutableListOf<HttpRequestData>()
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("unset") }
    private var tokensIssued = 0

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun api(configured: Boolean = true, oauth: AppOAuthClient? = OAUTH): HttpGitHubApi {
        val engine = MockEngine { request ->
            calls += request
            if (request.url.encodedPath.endsWith("/access_tokens")) {
                tokensIssued++
                val expires = clock.now + 60.minutes
                json("""{"token":"ghs_$tokensIssued","expires_at":"$expires"}""", HttpStatusCode.Created)
            } else {
                handler(request)
            }
        }
        val http = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }
        val jwt = if (configured) AppJwt("123", pem(), clock) else null
        return HttpGitHubApi(http, "https://api.github.test", { jwt }, clock, "https://github.test") { oauth }
    }

    private suspend fun pem(): String = CryptographyProvider.Default.get(RSA.PKCS1)
        .keyPairGenerator(keySize = 2048.bits, digest = SHA256).generateKey().privateKey
        .encodeToByteArray(RSA.PrivateKey.Format.PEM.PKCS1).decodeToString()

    private fun pull(state: String, merged: Boolean) =
        """{"number":6,"state":"$state","merged":$merged,"head":{"sha":"$SHA","ref":"feat"},""" +
            """"base":{"ref":"main"},"user":{"login":"dev"},"title":"x"}"""

    @Test
    fun aPullRequestIsReadWithAnInstallationTokenKeptUntilAMinuteBeforeItExpires() = runTest {
        handler = { request ->
            when (request.url.encodedPath) {
                "/repos/acme/api/pulls/6" -> json(pull("open", merged = false))
                "/repos/acme/api/pulls/7" -> json(pull("closed", merged = true))
                "/repos/acme/api/pulls/8" -> json(pull("open", merged = true))
                else -> json("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
            }
        }
        val github = api()
        assertEquals(GitHubPull(6, SHA, "main", open = true, author = "dev"), github.pullRequest(42, "acme/api", 6))
        assertFalse(github.pullRequest(42, "acme/api", 7)?.open ?: true, "Closed")
        assertFalse(github.pullRequest(42, "acme/api", 8)?.open ?: true, "Merged")
        assertNull(github.pullRequest(42, "acme/api", 9))
        assertEquals(1, tokensIssued, "One installation token for every call")

        val exchange = calls.first()
        assertEquals(HttpMethod.Post, exchange.method)
        assertEquals("/app/installations/42/access_tokens", exchange.url.encodedPath)
        assertTrue(exchange.headers[HttpHeaders.Authorization].orEmpty().startsWith("Bearer ey"), "The App's JWT")
        val permissions = Json.parseToJsonElement((exchange.body as TextContent).text).jsonObject
        assertEquals("""{"checks":"write","pull_requests":"read"}""", permissions.getValue("permissions").toString())
        assertEquals("Bearer ghs_1", calls[1].headers[HttpHeaders.Authorization])
        assertEquals("2022-11-28", calls[1].headers["X-GitHub-Api-Version"])

        clock.now += 59.minutes
        github.pullRequest(42, "acme/api", 6)
        assertEquals(2, tokensIssued, "Renewed a minute before it expires")
        github.pullRequest(43, "acme/api", 6)
        assertEquals(3, tokensIssued, "One token per installation")
    }

    @Test
    fun aCheckIsCreatedThenUpdatedWithItsOutput() = runTest {
        handler = { json("""{"id":77}""", HttpStatusCode.Created) }
        val github = api()
        val waiting = CheckRun(SHA, "in_progress", null, "Waiting", "Gate", "https://c/yes/1", "1")
        assertEquals(77, github.publishCheck(42, "acme/api", waiting, null))
        val created = calls.last()
        assertEquals(HttpMethod.Post to "/repos/acme/api/check-runs", created.method to created.url.encodedPath)
        val body = Json.parseToJsonElement((created.body as TextContent).text).jsonObject
        assertEquals("ostrom-gate", body.getValue("name").jsonPrimitive.content)
        assertEquals(SHA, body.getValue("head_sha").jsonPrimitive.content)
        assertFalse("conclusion" in body)
        assertEquals("""{"title":"Waiting","summary":"Gate"}""", body.getValue("output").toString())

        val done = waiting.copy(status = "completed", conclusion = "success", title = "Approved by lead")
        assertEquals(77, github.publishCheck(42, "acme/api", done, 77))
        val updated = calls.last()
        assertEquals(HttpMethod.Patch to "/repos/acme/api/check-runs/77", updated.method to updated.url.encodedPath)
        val patch = Json.parseToJsonElement((updated.body as TextContent).text).jsonObject
        assertEquals("success", patch.getValue("conclusion").jsonPrimitive.content)
        assertEquals(NOW.toString(), patch.getValue("completed_at").jsonPrimitive.content)
    }

    @Test
    fun aCommitsCheckRunsAreReadWithTheInstallationTokenUnderTheirName() = runTest {
        handler = { request ->
            when (request.url.encodedPath) {
                "/repos/acme/api/commits/$SHA/check-runs" -> json(
                    """{"total_count":2,"check_runs":[""" +
                        """{"id":5,"name":"unit tests","head_sha":"$SHA","status":"completed",""" +
                        """"conclusion":"success",""" +
                        """"app":{"slug":"github-actions"},"html_url":"https://github.test/r/5",""" +
                        """"completed_at":"2026-10-30T04:05:00Z"},""" +
                        """{"id":6,"name":"unit tests","head_sha":"$SHA","status":"in_progress",""" +
                        """"app":{"slug":"circleci"}}]}""",
                )
                "/repos/acme/gone/commits/$SHA/check-runs" -> json("{}", HttpStatusCode.NotFound)
                "/repos/acme/api/commits/${"0".repeat(40)}/check-runs" -> json("{}", HttpStatusCode.UnprocessableEntity)
                else -> json("{}", HttpStatusCode.InternalServerError)
            }
        }
        val github = api()
        val runs = github.checkRuns(42, "acme/api", SHA, "unit tests")
        val url = "https://github.test/r/5"
        val done = GitHubCiRun(5, "unit tests", SHA, "completed", "success", "github-actions", url, NOW + 5.minutes)
        val running = GitHubCiRun(6, "unit tests", SHA, "in_progress", null, "circleci", null, null)
        assertEquals(listOf(done, running), runs)
        val read = calls.last()
        assertEquals(HttpMethod.Get, read.method)
        assertEquals("check_name=unit%20tests&filter=latest&per_page=100", read.url.encodedQuery)
        assertEquals("Bearer ghs_1", read.headers[HttpHeaders.Authorization], "The gate check's installation token")
        assertEquals(emptyList(), github.checkRuns(42, "acme/gone", SHA, "unit tests"), "Not reached")
        assertEquals(emptyList(), github.checkRuns(42, "acme/api", "0".repeat(40), "unit tests"), "Unknown commit")
        val failed = assertFailsWith<GitHubCallException> { github.checkRuns(42, "acme/down", SHA, "unit tests") }
        assertEquals("GitHub refused the check runs (500).", failed.message)
    }

    @Test
    fun anInstallationIsReadWithTheAppJwt() = runTest {
        handler = { request ->
            if (request.url.encodedPath == "/app/installations/42") {
                json("""{"id":42,"account":{"login":"acme"}}""")
            } else {
                json("{}", HttpStatusCode.NotFound)
            }
        }
        val github = api()
        assertEquals("acme", github.installationAccount(42))
        assertNull(github.installationAccount(7))
        assertEquals(0, tokensIssued)
        assertTrue(calls.first().headers[HttpHeaders.Authorization].orEmpty().startsWith("Bearer ey"))
    }

    @Test
    fun gitHubFailingIsAGitHubCallExceptionWithoutAToken() = runTest {
        handler = { json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }
        val refused = assertFailsWith<GitHubCallException> { api().installationAccount(42) }
        assertEquals("GitHub refused the installation (401).", refused.message)

        handler = { json("""{"unexpected":true}""") }
        val odd = assertFailsWith<GitHubCallException> { api().pullRequest(42, "acme/api", 6) }
        assertEquals("GitHub answered an unexpected pull request.", odd.message)

        handler = { throw IllegalStateException("connection refused to https://api.github.test") }
        val down = assertFailsWith<GitHubCallException> { api().installationAccount(42) }
        assertEquals("GitHub did not answer GET /app/installations/42.", down.message)

        val unconfigured = assertFailsWith<GitHubCallException> { api(configured = false).pullRequest(42, "a/b", 1) }
        assertEquals("The GitHub App is not configured on this server.", unconfigured.message)

        handler = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { api().installationAccount(42) }
    }

    @Test
    fun theCodeOfTheInstallIsExchangedForThePersonsTokenWithTheAppsOAuthClient() = runTest {
        handler = { json("""{"access_token":"gho_person","token_type":"bearer","scope":""}""") }
        assertEquals("gho_person", api().userToken("the-code"))
        val exchange = calls.single()
        assertEquals(HttpMethod.Post, exchange.method)
        assertEquals("https://github.test/login/oauth/access_token", exchange.url.toString())
        assertEquals("application/json", exchange.headers[HttpHeaders.Accept])
        val form = (exchange.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals("client_id=Iv1.test&client_secret=client-secret&code=the-code", form)
        assertNull(exchange.headers[HttpHeaders.Authorization], "No App JWT on the OAuth exchange")

        // GitHub answers 200 with an error for a code it refuses.
        handler = { json("""{"error":"bad_verification_code","error_description":"The code is incorrect."}""") }
        assertNull(api().userToken("used"))

        handler = { json("""{"message":"down"}""", HttpStatusCode.ServiceUnavailable) }
        val down = assertFailsWith<GitHubCallException> { api().userToken("the-code") }
        assertEquals("GitHub refused the authorization (503).", down.message)

        val none = assertFailsWith<GitHubCallException> { api(oauth = null).userToken("the-code") }
        assertEquals("The GitHub App has no OAuth client on this server.", none.message)
        assertEquals("AppOAuthClient(id=Iv1.test)", OAUTH.toString(), "The secret never shows")
    }

    @Test
    fun thePersonsInstallationsAreReadPageByPageWithTheirToken() = runTest {
        handler = { request ->
            val page = request.url.parameters["page"]?.toInt() ?: 0
            val count = if (page == 1) 100 else 2
            val ids = (1..count).joinToString(",") { """{"id":${page * 1000 + it},"account":{"login":"x"}}""" }
            json("""{"total_count":102,"installations":[$ids]}""")
        }
        val ids = api().userInstallations("gho_person")
        assertEquals(102, ids.size)
        assertTrue(1001L in ids && 2002L in ids)
        assertEquals(listOf("1", "2"), calls.map { it.url.parameters["page"] })
        assertTrue(calls.all { it.url.encodedPath == "/user/installations" && it.url.parameters["per_page"] == "100" })
        assertEquals("Bearer gho_person", calls.first().headers[HttpHeaders.Authorization])

        // A person reaching thousands of installations: the read stops after 10 pages.
        calls.clear()
        handler = { request ->
            val page = request.url.parameters["page"]!!.toInt()
            json("""{"installations":[${(1..100).joinToString(",") { """{"id":${page * 1000 + it}}""" }}]}""")
        }
        assertEquals(1000, api().userInstallations("gho_many").size)
        assertEquals(10, calls.size)

        handler = { json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }
        val refused = assertFailsWith<GitHubCallException> { api().userInstallations("gho_revoked") }
        assertEquals("GitHub refused the installations (401).", refused.message)
        handler = { json("""{}""") }
        assertEquals(emptySet(), api().userInstallations("gho_nobody"))
    }

    @Test
    fun thePersonsLoginIsReadWithTheirToken() = runTest {
        handler = { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer gho_person") {
                json("""{"login":"acme-admin","id":7}""")
            } else {
                json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized)
            }
        }
        assertEquals("acme-admin", api().userLogin("gho_person"))
        assertEquals(HttpMethod.Get to "/user", calls.last().method to calls.last().url.encodedPath)
        val refused = assertFailsWith<GitHubCallException> { api().userLogin("gho_revoked") }
        assertEquals("GitHub refused the person (401).", refused.message)
    }

    @Test
    fun onlyARepositorysFullNameReachesGitHubsPath() = runTest {
        handler = { json("""{"id":77}""", HttpStatusCode.Created) }
        val github = api()
        val check = CheckRun(SHA, "in_progress", null, "Waiting", "Gate", "https://c/yes/1", "1")
        for (repo in listOf("x/../../orgs/acme/members", "acme/api?per_page=1", "acme", "acme/..", "acme/api/pulls")) {
            val refused = assertFailsWith<GitHubCallException>(repo) { github.pullRequest(42, repo, 6) }
            assertEquals("This is not a repository's full name.", refused.message)
            assertFailsWith<GitHubCallException>(repo) { github.publishCheck(42, repo, check, null) }
        }
        assertTrue(calls.none { "/repos/" in it.url.encodedPath }, "Nothing sent to GitHub")
        assertEquals(77, github.publishCheck(42, "acme/.github", check, null), "A dotted name is a repository")
    }
}

private val OAUTH = AppOAuthClient("Iv1.test", "client-secret")
