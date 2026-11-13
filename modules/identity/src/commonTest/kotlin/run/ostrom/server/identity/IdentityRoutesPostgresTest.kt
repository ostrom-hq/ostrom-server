// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.IssueAgentTokenUseCase
import run.ostrom.server.identity.domain.PERSONAL_TOKEN_SCOPES
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.presentation.AccountResponse
import run.ostrom.server.identity.presentation.AnswerDeviceRequest
import run.ostrom.server.identity.presentation.AuditEventsResponse
import run.ostrom.server.identity.presentation.AuthSessionsResponse
import run.ostrom.server.identity.presentation.AuthTokenResponse
import run.ostrom.server.identity.presentation.CreateTokenRequest
import run.ostrom.server.identity.presentation.CreatedTokenResponse
import run.ostrom.server.identity.presentation.DeviceAuthorizationResponse
import run.ostrom.server.identity.presentation.DeviceCodeRequest
import run.ostrom.server.identity.presentation.DeviceCodeResponse
import run.ostrom.server.identity.presentation.DeviceTokenRequest
import run.ostrom.server.identity.presentation.MeDevResponse
import run.ostrom.server.identity.presentation.MeResponse
import run.ostrom.server.identity.presentation.TokenScopesResponse
import run.ostrom.server.identity.presentation.TokensResponse
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
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
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/tokens/scopes").status)
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
    fun theScopeCatalogueIsServedToASessionAndEnforced() = serverTest(listOf(IdentityModule)) { server ->
        val (_, started) = server.signIn()

        val response = server.client.get("/api/v1/tokens/scopes") { session(started, csrf = false) }
        assertEquals(HttpStatusCode.OK, response.status)
        val catalogue = response.body<TokenScopesResponse>().scopes
        assertEquals(
            PERSONAL_TOKEN_SCOPES.map { it.scope to it.description },
            catalogue.map { it.scope to it.description },
        )
        assertTrue(catalogue.none { it.scope in setOf("gates:answer", "agent:session", "user:*") })

        // A token reads its own profile, never the catalogue: tokens are managed from the console.
        val token = server.client.post("/api/v1/tokens") {
            session(started)
            json(CreateTokenRequest("t", listOf("profile:read")))
        }.body<CreatedTokenResponse>()
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.get("/api/v1/tokens/scopes") { bearerAuth(token.secret) }.status,
        )

        // Every scope of the catalogue can be given (32 at most per token); one outside it is a 400 that names it.
        catalogue.map { it.scope }.chunked(32).forEachIndexed { index, scopes ->
            val created = server.client.post("/api/v1/tokens") {
                session(started)
                json(CreateTokenRequest("all-$index", scopes))
            }
            assertEquals(HttpStatusCode.Created, created.status, scopes.toString())
        }
        for (retired in listOf("deploy:write", "billing:manage", "gates:answer")) {
            val outside = server.client.post("/api/v1/tokens") {
                session(started)
                json(CreateTokenRequest("t", listOf("profile:read", retired)))
            }
            assertEquals(HttpStatusCode.BadRequest, outside.status)
            val error = outside.body<ApiError>().error
            assertEquals("invalid_request", error.type)
            assertTrue(error.message.endsWith("Not in it: $retired."), error.message)
        }
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
    fun theConsoleSeesWhenADeviceLoginWasAsked() = serverTest(listOf(IdentityModule)) { server ->
        val (_, started) = server.signIn()
        val codes =
            server.client.post("/api/v1/auth/device") { json(DeviceCodeRequest("laptop")) }.body<DeviceCodeResponse>()

        val shown = server.client.get("/api/v1/auth/device?userCode=${codes.userCode}") { session(started) }
            .body<DeviceAuthorizationResponse>()
        // Web audit 2, W3: when the CLI asked, the request's whole lifetime before it expires.
        assertEquals(10.minutes, Instant.parse(shown.expiresAt) - Instant.parse(shown.requestedAt))
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
    fun aPersonSeesAndEndsOnlyTheirOwnBrowserSessions() = serverTest(listOf(IdentityModule)) { server ->
        val (ada, here) = server.signIn("1")
        val elsewhere = server.koin.get<StartWebSessionUseCase>()(ada.id)
        val (_, bobs) = server.signIn("2")
        val token = server.client.post("/api/v1/tokens") {
            session(here)
            json(CreateTokenRequest("ci", listOf("profile:read")))
        }.body<CreatedTokenResponse>().secret

        val listed = server.client.get("/api/v1/auth/sessions") { session(here, csrf = false) }
        assertEquals(HttpStatusCode.OK, listed.status)
        val items = listed.body<AuthSessionsResponse>().items
        assertEquals(setOf(here.session.id.toString(), elsewhere.session.id.toString()), items.map { it.id }.toSet())
        assertEquals(listOf(here.session.id.toString()), items.filter { it.current }.map { it.id })
        // A token is not a browser session: it neither lists nor ends one.
        assertEquals(HttpStatusCode.Forbidden, server.client.get("/api/v1/auth/sessions") { bearerAuth(token) }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.delete("/api/v1/auth/sessions/${elsewhere.session.id}") { bearerAuth(token) }.status,
        )

        // Another person's session, an unknown one and a malformed id get the same 404, and Bob stays signed in.
        for (id in listOf(bobs.session.id.toString(), Uuid.random().toString(), "not-an-id")) {
            val refused = server.client.delete("/api/v1/auth/sessions/$id") { session(here) }
            assertEquals(HttpStatusCode.NotFound, refused.status, id)
        }
        assertEquals(HttpStatusCode.OK, server.client.get("/api/v1/auth/me") { session(bobs) }.status)

        // Ending another of one's sessions signs that browser out, not this one.
        val other = server.client.delete("/api/v1/auth/sessions/${elsewhere.session.id}") { session(here) }
        assertEquals(HttpStatusCode.NoContent, other.status)
        assertTrue(other.headers.getAll(HttpHeaders.SetCookie).orEmpty().none { it.startsWith("$SESSION_COOKIE=;") })
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/auth/me") { session(elsewhere) }.status)
        assertEquals(HttpStatusCode.OK, server.client.get("/api/v1/auth/me") { session(here) }.status)

        // Ending the current one is signing out here: its cookie is cleared.
        val current = server.client.delete("/api/v1/auth/sessions/${here.session.id}") { session(here) }
        assertEquals(HttpStatusCode.NoContent, current.status)
        assertTrue(current.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$SESSION_COOKIE=;") })
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/auth/me") { session(here) }.status)
    }

    @Test
    fun startingDeviceLoginsIsRateLimitedPerAddress() = serverTest(listOf(IdentityModule)) { server ->
        val statuses = (1..11).map { server.client.post("/api/v1/auth/device") { json(DeviceCodeRequest()) }.status }

        assertEquals(List(10) { HttpStatusCode.OK } + HttpStatusCode.TooManyRequests, statuses)
    }
}
