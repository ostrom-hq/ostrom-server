// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.IssueAgentTokenUseCase
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.presentation.AnswerDeviceRequest
import dev.ostrom.server.identity.presentation.AuditEventsResponse
import dev.ostrom.server.identity.presentation.AuthTokenResponse
import dev.ostrom.server.identity.presentation.CreateTokenRequest
import dev.ostrom.server.identity.presentation.CreatedTokenResponse
import dev.ostrom.server.identity.presentation.DeviceAuthorizationResponse
import dev.ostrom.server.identity.presentation.DeviceCodeRequest
import dev.ostrom.server.identity.presentation.DeviceCodeResponse
import dev.ostrom.server.identity.presentation.DeviceTokenRequest
import dev.ostrom.server.identity.presentation.AccountResponse
import dev.ostrom.server.identity.presentation.MeDevResponse
import dev.ostrom.server.identity.presentation.MeResponse
import dev.ostrom.server.identity.presentation.TokensResponse
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.ORG_HEADER
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.http.ApiError
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** The identity routes end to end: real HTTP through the kernel, real PostgreSQL, real credentials. */
class IdentityRoutesPostgresTest {
    private suspend fun TestServer.signIn(subject: String = "1"): Pair<User, StartedSession> {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, subject, "user$subject@example.com", true, "User $subject"),
        )
        return user to koin.get<StartWebSessionUseCase>()(user.id)
    }

    private suspend fun TestServer.pollDevice(deviceCode: String): HttpResponse =
        client.post("/api/v1/auth/device/token") { json(DeviceTokenRequest(deviceCode)) }

    /** A poll that gives no token: its status and its error type. */
    private suspend fun HttpResponse.refusal(): Pair<Int, String> = status.value to body<ApiError>().error.type

    private fun HttpRequestBuilder.session(started: StartedSession, csrf: Boolean = true) =
        browserSession(started.secret, started.csrfToken.takeIf { csrf })

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun withoutCredentialsEveryAccountRouteIs401() = serverTest(listOf(IdentityModule)) { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/auth/me").status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/tokens").status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.post("/api/v1/auth/logout").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            server.client.get("/api/v1/auth/me") { bearerAuth("osp_forged") }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            server.client.get("/api/v1/auth/me") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=oss_forged") }.status,
        )
    }

    @Test
    fun aBrowserSessionReadsTheProfileWithItsCsrfToken() = serverTest(listOf(IdentityModule)) { server ->
        val (user, started) = server.signIn()

        val response = server.client.get("/api/v1/auth/me") { session(started, csrf = false) }
        assertEquals(HttpStatusCode.OK, response.status)
        // The contract's `Me`: the person, what the session may do, and its CSRF token.
        val me = response.body<MeResponse>()
        assertEquals(MeDevResponse(user.login, verified = true, via = "github"), me.dev)
        assertEquals(listOf("user:*"), me.capabilities)
        assertEquals(started.csrfToken, me.csrfToken)
        assertNull(me.member)
        // The account settings read the e-mails and ways in on their own route.
        val account = server.client.get("/api/v1/auth/me/account") { session(started, csrf = false) }
        assertEquals(HttpStatusCode.OK, account.status)
        val profile = account.body<AccountResponse>()
        assertEquals(user.id.toString(), profile.id)
        assertEquals("user1@example.com", profile.primaryEmail)
        assertEquals(listOf("github"), profile.providers)
        // Without the orgs module no organization is a membership: the header is ignored, never an error.
        val named = server.client.get("/api/v1/auth/me") {
            session(started, csrf = false)
            header(ORG_HEADER, Uuid.random().toString())
        }
        assertEquals(HttpStatusCode.OK, named.status)
        assertNull(named.body<MeResponse>().organization)
    }

    @Test
    fun aStateChangingRequestWithoutTheCsrfTokenIsRefused() = serverTest(listOf(IdentityModule)) { server ->
        val (_, started) = server.signIn()

        val forged = server.client.post("/api/v1/tokens") {
            session(started, csrf = false)
            json(CreateTokenRequest("t", listOf("profile:read")))
        }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        val wrong = server.client.post("/api/v1/tokens") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}")
            header(CSRF_HEADER, "not-the-token")
            json(CreateTokenRequest("t", listOf("profile:read")))
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    }

    @Test
    fun personalTokensAreCreatedUsedRotatedAndRevoked() = serverTest(listOf(IdentityModule)) { server ->
        val (user, started) = server.signIn()

        val created = server.client.post("/api/v1/tokens") {
            session(started)
            json(CreateTokenRequest("ci", listOf("profile:read"), expiresInDays = 30))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val token = created.body<CreatedTokenResponse>()
        assertTrue(token.secret.startsWith("osp_"))
        assertEquals("personal", token.token.kind)

        // The token reads what its scopes allow, never the token management.
        assertEquals(
            user.id.toString(),
            server.client.get("/api/v1/auth/me/account") { bearerAuth(token.secret) }.body<AccountResponse>().id,
        )
        val byToken = server.client.get("/api/v1/auth/me") { bearerAuth(token.secret) }.body<MeResponse>()
        assertNull(byToken.csrfToken)
        assertEquals(listOf("profile:read"), byToken.capabilities, "What the token may do")
        // A token wins over a cookie sent along: the answer carries no CSRF token, the request is not a session.
        val both = server.client.get("/api/v1/auth/me") {
            bearerAuth(token.secret)
            session(started, csrf = false)
        }
        assertNull(both.body<MeResponse>().csrfToken)
        assertEquals(HttpStatusCode.Forbidden, server.client.get("/api/v1/tokens") { bearerAuth(token.secret) }.status)

        val listed = server.client.get("/api/v1/tokens") { session(started) }.body<TokensResponse>()
        assertEquals(listOf(token.token.id), listed.tokens.map { it.id })

        val rotated = server.client.post("/api/v1/tokens/${token.token.id}/rotate") { session(started) }
        assertEquals(HttpStatusCode.Created, rotated.status)
        val replacement = rotated.body<CreatedTokenResponse>()
        assertEquals(
            HttpStatusCode.Unauthorized,
            server.client.get("/api/v1/auth/me") { bearerAuth(token.secret) }.status,
        )
        assertEquals(HttpStatusCode.OK, server.client.get("/api/v1/auth/me") { bearerAuth(replacement.secret) }.status)

        assertEquals(
            HttpStatusCode.NoContent,
            server.client.delete("/api/v1/tokens/${replacement.token.id}") { session(started) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            server.client.get("/api/v1/auth/me") { bearerAuth(replacement.secret) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.delete("/api/v1/tokens/${replacement.token.id}") { session(started) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.delete("/api/v1/tokens/not-a-uuid") { session(started) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.post("/api/v1/tokens/not-a-uuid/rotate") { session(started) }.status,
        )

        val actions =
            server.client.get("/api/v1/auth/me/audit?limit=2") { session(started) }.body<AuditEventsResponse>()
        assertEquals(listOf("token.revoked", "token.rotated"), actions.events.map { it.action })
        assertNotNull(actions.nextBefore)
    }

    @Test
    fun anotherUsersTokenIsNotFound() = serverTest(listOf(IdentityModule)) { server ->
        val (_, ada) = server.signIn("1")
        val (_, bob) = server.signIn("2")
        val token = server.client.post("/api/v1/tokens") {
            session(ada)
            json(CreateTokenRequest("t", listOf("profile:read")))
        }.body<CreatedTokenResponse>()

        assertEquals(
            HttpStatusCode.NotFound,
            server.client.delete("/api/v1/tokens/${token.token.id}") { session(bob) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.post("/api/v1/tokens/${token.token.id}/rotate") { session(bob) }.status,
        )
        assertEquals(emptyList(), server.client.get("/api/v1/tokens") { session(bob) }.body<TokensResponse>().tokens)
    }

    @Test
    fun invalidTokenRequestsAre400() = serverTest(listOf(IdentityModule)) { server ->
        val (_, started) = server.signIn()

        val badScopes =
            server.client.post("/api/v1/tokens") { session(started); json(CreateTokenRequest("t", emptyList())) }
        assertEquals(HttpStatusCode.BadRequest, badScopes.status)
        val malformed = server.client.post("/api/v1/tokens") {
            session(started)
            contentType(ContentType.Application.Json)
            setBody("{\"name\":")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    @Test
    fun anAgentTokenReachesNoAccountRoute() = serverTest(listOf(IdentityModule)) { server ->
        val (user, _) = server.signIn()
        val agent = server.koin.get<IssueAgentTokenUseCase>()(user.id, Uuid.random(), "run-1", 1.hours)

        assertEquals(HttpStatusCode.Forbidden, server.client.get("/api/v1/auth/me") { bearerAuth(agent.secret) }.status)
        assertEquals(HttpStatusCode.Forbidden, server.client.get("/api/v1/tokens") { bearerAuth(agent.secret) }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.post("/api/v1/auth/logout") { bearerAuth(agent.secret) }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.post("/api/v1/tokens") {
                bearerAuth(agent.secret)
                json(CreateTokenRequest("t", listOf("orgs:write")))
            }.status,
        )
    }

    @Test
    fun theCliLogsInWithTheDeviceFlowAndLogsOut() = serverTest(listOf(IdentityModule)) { server ->
        val (user, started) = server.signIn()

        val codes =
            server.client.post("/api/v1/auth/device") { json(DeviceCodeRequest("laptop")) }.body<DeviceCodeResponse>()
        assertEquals("http://localhost:5173/device", codes.verificationUri)
        assertEquals("http://localhost:5173/device?code=${codes.userCode}", codes.verificationUriComplete)
        assertEquals(600L, codes.expiresIn)
        assertEquals(5L, codes.interval)
        // The contract's `pollDeviceLogin`, as the CLI reads it: 428 while pending, RFC 8628's codes otherwise.
        assertEquals(428 to "authorization_pending", server.pollDevice(codes.deviceCode).refusal())
        assertEquals(400 to "slow_down", server.pollDevice(codes.deviceCode).refusal())

        val shown = server.client.get("/api/v1/auth/device?userCode=${codes.userCode}") { session(started) }
        assertEquals("laptop", shown.body<DeviceAuthorizationResponse>().clientName)
        assertEquals(HttpStatusCode.BadRequest, server.client.get("/api/v1/auth/device") { session(started) }.status)
        val answered = server.client.post("/api/v1/auth/device/answer") {
            session(started)
            json(AnswerDeviceRequest(codes.userCode, true))
        }
        assertEquals(HttpStatusCode.NoContent, answered.status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.post("/api/v1/auth/device/answer") {
                session(started)
                json(AnswerDeviceRequest(codes.userCode, true))
            }.status,
        )

        // The CLI polled twice in a row: the next poll after the interval gets the token. The test does not wait:
        // a poll that comes too soon after an approval still gets it (only pending polls are slowed down).
        val answer = server.pollDevice(codes.deviceCode)
        assertEquals(HttpStatusCode.OK, answer.status)
        val approved = answer.body<AuthTokenResponse>()
        assertEquals(user.login, approved.login)
        assertTrue("profile:read" in approved.scopes)
        assertNotNull(approved.expiresAt)
        val secret = approved.token
        assertTrue(secret.startsWith("osc_"))
        assertEquals(
            user.id.toString(),
            server.client.get("/api/v1/auth/me/account") { bearerAuth(secret) }.body<AccountResponse>().id,
        )
        assertEquals(400 to "expired_token", server.pollDevice(codes.deviceCode).refusal())
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.post("/api/v1/auth/device/token") { json(DeviceTokenRequest("unknown")) }.status,
        )

        // The CLI token cannot approve another login: that needs the browser.
        // A CLI may start without a body (the contract's `POST /api/v1/auth/device`).
        val other = server.client.post("/api/v1/auth/device").body<DeviceCodeResponse>()
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.post("/api/v1/auth/device/answer") {
                bearerAuth(secret)
                json(AnswerDeviceRequest(other.userCode, true))
            }.status,
        )
        val denied = server.client.post("/api/v1/auth/device/answer") {
            session(started)
            json(AnswerDeviceRequest(other.userCode, false))
        }
        assertEquals(HttpStatusCode.NoContent, denied.status)
        assertEquals(400 to "access_denied", server.pollDevice(other.deviceCode).refusal())

        // `ostrom logout` revokes the CLI token.
        assertEquals(HttpStatusCode.NoContent, server.client.post("/api/v1/auth/logout") { bearerAuth(secret) }.status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/auth/me") { bearerAuth(secret) }.status)
    }

    @Test
    fun signingOutEndsTheSessionAndClearsTheCookie() = serverTest(listOf(IdentityModule)) { server ->
        val (user, started) = server.signIn()
        val second = server.koin.get<StartWebSessionUseCase>()(user.id)
        val third = server.koin.get<StartWebSessionUseCase>()(user.id)

        val response = server.client.post("/api/v1/auth/logout") { session(started) }
        assertEquals(HttpStatusCode.NoContent, response.status)
        val cookie = assertNotNull(response.headers[HttpHeaders.SetCookie])
        assertTrue(cookie.startsWith("$SESSION_COOKIE=;"), cookie)
        assertTrue("HttpOnly" in cookie && "SameSite=Lax" in cookie, cookie)
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/auth/me") { session(started) }.status)
        assertEquals(HttpStatusCode.OK, server.client.get("/api/v1/auth/me") { session(second) }.status)

        assertEquals(
            HttpStatusCode.NoContent,
            server.client.post("/api/v1/auth/logout/everywhere") { session(second) }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/auth/me") { session(third) }.status)
    }

    @Test
    fun startingDeviceLoginsIsRateLimitedPerAddress() = serverTest(listOf(IdentityModule)) { server ->
        val statuses = (1..11).map { server.client.post("/api/v1/auth/device") { json(DeviceCodeRequest()) }.status }

        assertEquals(List(10) { HttpStatusCode.OK } + HttpStatusCode.TooManyRequests, statuses)
    }
}
