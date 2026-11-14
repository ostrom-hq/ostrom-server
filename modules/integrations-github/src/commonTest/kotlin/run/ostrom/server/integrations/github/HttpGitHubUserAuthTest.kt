// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GitHubAppMisconfiguredException
import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.domain.GitHubDeviceFlowDisabledException
import run.ostrom.server.integrations.github.domain.GitHubDevicePoll
import run.ostrom.server.integrations.github.domain.GitHubIdentity
import run.ostrom.server.integrations.github.domain.GitHubLinkNotFoundException
import run.ostrom.server.integrations.github.domain.GitHubRefresh
import run.ostrom.server.integrations.github.infrastructure.HttpGitHubUserAuth
import run.ostrom.server.kernel.errors.OstromException
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
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class HttpGitHubUserAuthTest {
    private val clock = SettableClock()
    private val calls = mutableListOf<HttpRequestData>()
    private var answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("unset") }

    private val auth = HttpGitHubUserAuth(
        HttpClient(
            MockEngine { request ->
                calls += request
                answer(request)
            },
        ) {
            expectSuccess = false
            install(HttpTimeout)
        },
        { API },
        { WEB },
        clock,
    )

    /** GitHub answers [body] with [status]. */
    private fun answers(body: String, status: HttpStatusCode = HttpStatusCode.OK) {
        answer = { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
    }

    private fun form(request: HttpRequestData = calls.last()) =
        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()

    private fun device(fields: String) =
        """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"$WEB/login/device",$fields}"""

    @Test
    fun theDeviceFlowStartsWithTheClientIdAlone() = runTest {
        answers(device(""""expires_in":900,"interval":7"""))
        val started = auth.startDevice(CLIENT_ID)
        assertEquals(
            listOf("dc", "ABCD-1234", "$WEB/login/device"),
            listOf(started.deviceCode, started.userCode, started.verificationUri),
        )
        assertEquals(900 to 7, started.expiresInSeconds to started.intervalSeconds)
        val request = calls.single()
        assertEquals("$WEB/login/device/code", request.url.toString())
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        assertEquals(mapOf("client_id" to listOf(CLIENT_ID)), form().entries().associate { it.key to it.value })
        answers(device(""""expires_in":900"""))
        assertEquals(5, auth.startDevice(CLIENT_ID).intervalSeconds, "GitHub's default interval")
    }

    @Test
    fun anUnexpectedDeviceAnswerIsRefused() = runTest {
        val long = "x".repeat(2049)
        val bodies = listOf(
            """{"user_code":"ABCD-1234","verification_uri":"$WEB/login/device","expires_in":900}""",
            """{"device_code":" ","user_code":"ABCD-1234","verification_uri":"$WEB/login/device","expires_in":900}""",
            """{"device_code":"$long","user_code":"ABCD-1234","verification_uri":"$WEB/x","expires_in":900}""",
            """{"device_code":"dc","verification_uri":"$WEB/login/device","expires_in":900}""",
            """{"device_code":"dc","user_code":"<script>","verification_uri":"$WEB/login/device","expires_in":900}""",
            """{"device_code":"dc","user_code":"ABCD-1234","expires_in":900}""",
            """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://evil.test/x","expires_in":9}""",
            """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"$WEB/${"x".repeat(600)}",""" +
                """"expires_in":900}""",
            """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"$WEB/login/device"}""",
            device(""""expires_in":0"""),
            device(""""expires_in":3601"""),
            device(""""expires_in":"900""""),
            device(""""expires_in":900,"interval":0"""),
            device(""""expires_in":900,"interval":601"""),
            """{"device_code":42,"user_code":"ABCD-1234","verification_uri":"$WEB/login/device","expires_in":900}""",
        )
        for (body in bodies) {
            answers(body)
            assertFailsWith<GitHubCallException>(body) { auth.startDevice(CLIENT_ID) }
        }
        answers("[]")
        assertFailsWith<GitHubCallException> { auth.startDevice(CLIENT_ID) }
        answers("<html>", HttpStatusCode.BadGateway)
        assertFailsWith<GitHubCallException> { auth.startDevice(CLIENT_ID) }
        answers(device(""""expires_in":900"""), HttpStatusCode.InternalServerError)
        assertFailsWith<GitHubCallException> { auth.startDevice(CLIENT_ID) }
        answers("""{"error":"device_flow_disabled"}""")
        assertFailsWith<GitHubDeviceFlowDisabledException> { auth.startDevice(CLIENT_ID) }
        answers("""{"error":{"nested":true}}""", HttpStatusCode.BadRequest)
        assertFailsWith<GitHubCallException> { auth.startDevice(CLIENT_ID) }
    }

    @Test
    fun eachDevicePollAnswerHasItsMeaning() = runTest {
        answers("""{"error":"authorization_pending"}""")
        assertEquals(GitHubDevicePoll.Pending, auth.pollDevice(CLIENT_ID, "dc"))
        val sent = form()
        assertEquals(listOf(CLIENT_ID, "dc"), listOf(sent["client_id"], sent["device_code"]))
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", sent["grant_type"])
        assertEquals("$WEB/login/oauth/access_token", calls.last().url.toString())
        answers("""{"error":"slow_down","interval":20}""")
        assertEquals(GitHubDevicePoll.SlowDown(20), auth.pollDevice(CLIENT_ID, "dc"))
        answers("""{"error":"slow_down"}""")
        assertEquals(GitHubDevicePoll.SlowDown(10), auth.pollDevice(CLIENT_ID, "dc"))
        answers("""{"error":"slow_down","interval":100000}""")
        assertEquals(GitHubDevicePoll.SlowDown(600), auth.pollDevice(CLIENT_ID, "dc"))
        for (expired in listOf("expired_token", "incorrect_device_code")) {
            answers("""{"error":"$expired"}""")
            assertEquals(GitHubDevicePoll.Expired, auth.pollDevice(CLIENT_ID, "dc"))
        }
        answers("""{"error":"access_denied"}""")
        assertEquals(GitHubDevicePoll.Denied, auth.pollDevice(CLIENT_ID, "dc"))
        answers("""{"access_token":"ghu_abc","expires_in":28800,"refresh_token":"ghr_abc",""" +
            """"refresh_token_expires_in":15897600}""")
        val tokens = assertIs<GitHubDevicePoll.Authorized>(auth.pollDevice(CLIENT_ID, "dc")).tokens
        assertEquals("ghu_abc" to "ghr_abc", tokens.accessToken to tokens.refreshToken)
        assertEquals(NOW + 8.hours, tokens.accessExpiresAt)
        assertEquals(NOW + 15897600.seconds, tokens.refreshExpiresAt)
        answers("{}", HttpStatusCode.InternalServerError)
        assertFailsWith<GitHubCallException> { auth.pollDevice(CLIENT_ID, "dc") }
        answers("not json")
        assertFailsWith<GitHubCallException> { auth.pollDevice(CLIENT_ID, "dc") }
    }

    @Test
    fun gitHubsRefusalsBecomeTheirOwnKeysAndTheRestStaysAFailureOfGitHub() = runTest {
        val refusals = mapOf(
            "incorrect_client_credentials" to "github_app_misconfigured",
            "redirect_uri_mismatch" to "github_app_misconfigured",
            "bad_verification_code" to "github_link_not_found",
            "unverified_user_email" to "github_link_denied",
        )
        for ((error, type) in refusals) {
            answers("""{"error":"$error","error_description":"ghu_never_shown"}""")
            val refused = assertFailsWith<OstromException> {
                auth.pollDevice(CLIENT_ID, "dc")
            }
            assertEquals(type, refused.type)
        }
        answers("""{"error":"unsupported_grant_type"}""")
        val other = assertFailsWith<GitHubCallException> { auth.pollDevice(CLIENT_ID, "dc") }
        assertEquals("GitHub refused the authorization.", other.message)
    }

    @Test
    fun onlyGitHubAppUserTokensAreKept() = runTest {
        val bodies = listOf(
            """{"token_type":"bearer"}""",
            """{"access_token":" "}""",
            """{"access_token":"ghu_${"x".repeat(2048)}"}""",
        )
        for (body in bodies) {
            answers(body)
            assertFailsWith<GitHubCallException>(body) { auth.pollDevice(CLIENT_ID, "dc") }
        }
        // An OAuth App's token is the links' to refuse, and to revoke.
        answers("""{"access_token":"gho_oauth_app"}""")
        val oauth = assertIs<GitHubDevicePoll.Authorized>(auth.pollDevice(CLIENT_ID, "dc"))
        assertEquals("gho_oauth_app", oauth.tokens.accessToken)

        // An App that does not expire its tokens: no expiry, no refresh token.
        answers("""{"access_token":"ghu_forever","refresh_token":" ","refresh_token_expires_in":100}""")
        var tokens = assertIs<GitHubDevicePoll.Authorized>(auth.pollDevice(CLIENT_ID, "dc")).tokens
        assertNull(tokens.accessExpiresAt)
        assertNull(tokens.refreshToken)
        assertNull(tokens.refreshExpiresAt, "No expiry for a refresh token there is not")
        answers(
            """{"access_token":"ghu_a","expires_in":0,"refresh_token":"ghr_${"x".repeat(2048)}",""" +
                """"refresh_token_expires_in":0}""",
        )
        tokens = assertIs<GitHubDevicePoll.Authorized>(auth.pollDevice(CLIENT_ID, "dc")).tokens
        assertNull(tokens.accessExpiresAt)
        assertNull(tokens.refreshToken)
        answers("""{"access_token":"ghu_a","refresh_token":"ghr_a","refresh_token_expires_in":0}""")
        tokens = assertIs<GitHubDevicePoll.Authorized>(auth.pollDevice(CLIENT_ID, "dc")).tokens
        assertEquals("ghr_a", tokens.refreshToken)
        assertNull(tokens.refreshExpiresAt)
    }

    @Test
    fun theWebFlowUsesPkceAndTheClientSecret() = runTest {
        val url = Url(auth.authorizationUrl(CLIENT_ID, "https://ostrom.test/cb", "state-1", "challenge-1"))
        assertEquals("$WEB/login/oauth/authorize", "${url.protocol.name}://${url.host}${url.encodedPath}")
        assertEquals(CLIENT_ID, url.parameters["client_id"])
        assertEquals("https://ostrom.test/cb", url.parameters["redirect_uri"])
        assertEquals("state-1", url.parameters["state"])
        assertEquals("challenge-1", url.parameters["code_challenge"])
        assertEquals("S256", url.parameters["code_challenge_method"])

        answers("""{"access_token":"ghu_web","expires_in":28800,"refresh_token":"ghr_web"}""")
        val tokens = auth.exchangeCode(OAUTH_APP, "code-1", "https://ostrom.test/cb", "verifier-1")
        assertEquals("ghu_web", tokens.accessToken)
        val sent = form()
        assertEquals(
            listOf(CLIENT_ID, "client-secret", "code-1", "https://ostrom.test/cb", "verifier-1"),
            listOf("client_id", "client_secret", "code", "redirect_uri", "code_verifier").map { sent[it] },
        )
        answers("""{"error":"bad_verification_code"}""")
        assertFailsWith<GitHubLinkNotFoundException> { auth.exchangeCode(OAUTH_APP, "c", "r", "v") }
        answers("", HttpStatusCode.ServiceUnavailable)
        assertFailsWith<GitHubCallException> { auth.exchangeCode(OAUTH_APP, "c", "r", "v") }
        answers("not json")
        assertFailsWith<GitHubCallException>("A 200 that is not JSON") { auth.exchangeCode(OAUTH_APP, "c", "r", "v") }
    }

    @Test
    fun aRefreshSpendsTheRefreshTokenAndOnlyItsRefusalEndsTheLink() = runTest {
        answers("""{"access_token":"ghu_2","expires_in":28800,"refresh_token":"ghr_2"}""")
        val refreshed = assertIs<GitHubRefresh.Refreshed>(auth.refresh(OAUTH_APP, "ghr_1")).tokens
        assertEquals("ghu_2" to "ghr_2", refreshed.accessToken to refreshed.refreshToken)
        val sent = form()
        assertEquals("refresh_token" to "ghr_1", sent["grant_type"] to sent["refresh_token"])
        assertEquals("client-secret", sent["client_secret"])
        answers("""{"error":"bad_refresh_token"}""")
        assertEquals(GitHubRefresh.Rejected, auth.refresh(OAUTH_APP, "ghr_1"))
        answers("""{"error":"incorrect_client_credentials"}""")
        assertFailsWith<GitHubAppMisconfiguredException> { auth.refresh(OAUTH_APP, "ghr_1") }
        answers("{}", HttpStatusCode.BadGateway)
        assertFailsWith<GitHubCallException> { auth.refresh(OAUTH_APP, "ghr_1") }
        answers("[]")
        assertFailsWith<GitHubCallException>("A 200 that is not an object") { auth.refresh(OAUTH_APP, "ghr_1") }
    }

    @Test
    fun theAccountOfATokenIsReadFromTheApi() = runTest {
        answers("""{"id":7,"login":"octocat","name":"The Octocat"}""")
        assertEquals(GitHubIdentity(7, "octocat"), auth.user("ghu_abc"))
        val request = calls.single()
        assertEquals("$API/user", request.url.toString())
        assertEquals("Bearer ghu_abc", request.headers[HttpHeaders.Authorization])
        assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
        val bodies = listOf(
            """{"login":"octocat"}""",
            """{"id":"7","login":"octocat"}""",
            """{"id":0,"login":"octocat"}""",
            """{"id":7}""",
            """{"id":7,"login":"-bad"}""",
            "not json",
        )
        for (body in bodies) {
            answers(body)
            assertFailsWith<GitHubCallException>(body) { auth.user("ghu_abc") }
        }
        answers("""{"id":7,"login":"octocat"}""", HttpStatusCode.Unauthorized)
        assertFailsWith<GitHubCallException> { auth.user("ghu_abc") }
    }

    @Test
    fun aRevocationDeletesTheTokenWithTheAppsCredentials() = runTest {
        answer = { respond("", HttpStatusCode.NoContent) }
        auth.revoke(OAUTH_APP, "ghu_abc")
        val request = calls.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("$API/applications/$CLIENT_ID/token", request.url.toString())
        val basic = Base64.Default.encode("$CLIENT_ID:client-secret".encodeToByteArray())
        assertEquals("Basic $basic", request.headers[HttpHeaders.Authorization])
        assertEquals("""{"access_token":"ghu_abc"}""", (request.body as TextContent).text)
        answer = { respond("", HttpStatusCode.NotFound) }
        auth.revoke(OAUTH_APP, "ghu_abc")
        answer = { respond("", HttpStatusCode.UnprocessableEntity) }
        assertFailsWith<GitHubCallException> { auth.revoke(OAUTH_APP, "ghu_abc") }
    }

    @Test
    fun aBodyOverSixtyFourKibibytesIsRefusedUnread() = runTest {
        answers("""{"padding":"${"x".repeat(70_000)}"}""")
        val refused = assertFailsWith<GitHubCallException> { auth.user("ghu_abc") }
        assertEquals("GitHub answered too much on /user.", refused.message)
        answer = { throw IllegalStateException("connection refused") }
        assertFailsWith<GitHubCallException> { auth.user("ghu_abc") }
    }

    private companion object {
        const val API = "https://api.github.test"
        const val WEB = "https://github.test"
    }
}
