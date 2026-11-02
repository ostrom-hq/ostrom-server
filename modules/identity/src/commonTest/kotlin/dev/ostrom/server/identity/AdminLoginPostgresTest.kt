// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.ADMIN_SCOPES
import dev.ostrom.server.identity.domain.AuditRepository
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.presentation.AnswerDeviceRequest
import dev.ostrom.server.identity.presentation.AuthTokenResponse
import dev.ostrom.server.identity.presentation.DeviceAuthorizationResponse
import dev.ostrom.server.identity.presentation.DeviceCodeRequest
import dev.ostrom.server.identity.presentation.DeviceCodeResponse
import dev.ostrom.server.identity.presentation.DeviceTokenRequest
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.GovernanceScopes
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.STEP_UP_REQUIRED
import dev.ostrom.server.kernel.auth.auditGovernance
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.governs
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `ostrom login --admin` end to end on PostgreSQL: the governance scopes asked for, shown and approved in the browser
 * by an owner or admin only, a token bound to their organization for 8 hours, each governance write it makes
 * recorded in that organization's audit log, and `ostrom logout` revoking it.
 */
class AdminLoginPostgresTest {
    private val organization = Uuid.random()

    // The approvers' roles in [organization], by user id; a user without one belongs to no organization.
    private val roles = mutableMapOf<String, OrgRole>()

    private val overrides: Module = module {
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    roles[principal.subjectId]?.let { OrgPrincipal(organization, Uuid.random(), it) }
            }
        }
    }

    /** A governance route of another module: who governs may write, each token's write recorded first. */
    private object GovernedModule : ServerModule {
        override val name: String = "governed"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.authenticated {
                post("/governed") {
                    val principal = call.requirePrincipal()
                    if (principal.governs(GovernanceScopes.POLICY_WRITE)) {
                        call.auditGovernance(principal, GovernanceScopes.POLICY_WRITE)
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
            }
        }
    }

    private fun adminTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(IdentityModule, GovernedModule), overrides) { server -> block(server) }

    private suspend fun TestServer.signIn(subject: String, role: OrgRole?): Pair<User, StartedSession> {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, subject, "user$subject@example.com", true, "User $subject"),
        )
        role?.let { roles[user.id.toString()] = it }
        return user to koin.get<StartWebSessionUseCase>()(user.id)
    }

    private suspend fun TestServer.start(scopes: List<String>?) =
        client.post("/api/v1/auth/device") { json(DeviceCodeRequest("laptop", scopes)) }

    private suspend fun TestServer.answer(started: StartedSession, userCode: String, approve: Boolean = true) =
        client.post("/api/v1/auth/device/answer") {
            browserSession(started.secret, started.csrfToken)
            json(AnswerDeviceRequest(userCode, approve))
        }.status

    private suspend fun TestServer.poll(deviceCode: String) =
        client.post("/api/v1/auth/device/token") { json(DeviceTokenRequest(deviceCode)) }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun anOwnerOrAdminApprovesTheGovernanceScopesAndTheTokenWritesForEightHoursAudited() = adminTest { server ->
        val (admin, adminSession) = server.signIn("1", OrgRole.ADMIN)
        val (_, memberSession) = server.signIn("2", OrgRole.MEMBER)
        val (_, lonerSession) = server.signIn("3", null)
        val wrong = listOf("policy:write")
        assertEquals(HttpStatusCode.BadRequest, server.start(wrong).status, "All three or none")

        val codes = server.start(ADMIN_SCOPES.toList()).body<DeviceCodeResponse>()
        val shown = server.client.get("/api/v1/auth/device?userCode=${codes.userCode}") {
            browserSession(memberSession.secret, memberSession.csrfToken)
        }.body<DeviceAuthorizationResponse>()
        assertEquals(listOf("gates:policy", "harness:write", "policy:write"), shown.scopes, "Shown before the yes")
        assertEquals(HttpStatusCode.Forbidden, server.answer(memberSession, codes.userCode), "A member")
        assertEquals(HttpStatusCode.Forbidden, server.answer(lonerSession, codes.userCode), "No organization")
        assertEquals(HttpStatusCode.NoContent, server.answer(adminSession, codes.userCode))
        val before = Clock.System.now()

        val response = server.poll(codes.deviceCode)
        assertEquals(HttpStatusCode.OK, response.status)
        val token = response.body<AuthTokenResponse>()
        assertTrue(token.token.startsWith("osg_"))
        assertTrue(token.scopes.containsAll(ADMIN_SCOPES) && "profile:read" in token.scopes, "${token.scopes}")
        val expiresAt = Instant.parse(checkNotNull(token.expiresAt))
        assertTrue(expiresAt <= before + 8.hours + 1.minutes && expiresAt > before + 7.hours, "$expiresAt")

        val written = server.client.post("/governed") { bearerAuth(token.token) }
        assertEquals(HttpStatusCode.NoContent, written.status)
        val events = server.koin.get<AuditRepository>().list(organization, null, null, MAX_EVENTS).map { it.entry }
        val governed = events.single { it.action == "governance.write" }
        assertEquals(admin.id, governed.actorId)
        assertEquals("POST /governed", governed.targetId)
        assertEquals("policy:write", governed.details["scope"])
        val actions = events.map { it.action }.toSet()
        assertTrue(actions.containsAll(setOf("device.approved", "token.created")), "$actions")

        // The ordinary sign-in's token governs nothing and shows no scopes to approve.
        val ordinary = server.start(null).body<DeviceCodeResponse>()
        val plain = server.client.get("/api/v1/auth/device?userCode=${ordinary.userCode}") {
            browserSession(lonerSession.secret, lonerSession.csrfToken)
        }.body<DeviceAuthorizationResponse>()
        assertNull(plain.scopes)
        assertEquals(HttpStatusCode.NoContent, server.answer(lonerSession, ordinary.userCode))
        val cli = server.poll(ordinary.deviceCode).body<AuthTokenResponse>().token
        assertEquals(HttpStatusCode.Forbidden, server.client.post("/governed") { bearerAuth(cli) }.status)

        // `ostrom logout` revokes the elevated token in its organization.
        val loggedOut = server.client.post("/api/v1/auth/logout") { bearerAuth(token.token) }
        assertEquals(HttpStatusCode.NoContent, loggedOut.status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.post("/governed") { bearerAuth(token.token) }.status)
        val revoked = server.koin.get<AuditRepository>().list(organization, null, null, MAX_EVENTS)
        assertTrue(revoked.any { it.entry.action == "token.revoked" })
    }

    @Test
    fun aDenialOfAnAdminLoginNeedsNoRole() = adminTest { server ->
        val (_, session) = server.signIn("4", null)
        val codes = server.start(ADMIN_SCOPES.toList()).body<DeviceCodeResponse>()
        assertEquals(HttpStatusCode.NoContent, server.answer(session, codes.userCode, approve = false))
        assertEquals(HttpStatusCode.BadRequest, server.poll(codes.deviceCode).status, "access_denied")
    }

    @Test
    fun approvingAnAdminLoginNeedsASignInOfLessThanFiveMinutes() = adminTest { server ->
        val (admin, stale) = server.signIn("5", OrgRole.ADMIN)
        // Signed in six minutes ago.
        val aged = "UPDATE identity_web_sessions SET created_at = created_at - interval '6 minutes'"
        server.driver.execute("$aged WHERE user_id = '${admin.id}'").getOrThrow()
        val codes = server.start(ADMIN_SCOPES.toList()).body<DeviceCodeResponse>()
        val refused = server.client.post("/api/v1/auth/device/answer") {
            browserSession(stale.secret, stale.csrfToken)
            json(AnswerDeviceRequest(codes.userCode, true))
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(STEP_UP_REQUIRED in refused.bodyAsText(), refused.bodyAsText())

        // The old session still says no, and yes to the ordinary sign-in; signed in again, it approves the same code.
        val other = server.start(ADMIN_SCOPES.toList()).body<DeviceCodeResponse>()
        assertEquals(HttpStatusCode.NoContent, server.answer(stale, other.userCode, approve = false))
        val ordinary = server.start(null).body<DeviceCodeResponse>()
        assertEquals(HttpStatusCode.NoContent, server.answer(stale, ordinary.userCode))
        val (_, fresh) = server.signIn("5", OrgRole.ADMIN)
        assertEquals(HttpStatusCode.NoContent, server.answer(fresh, codes.userCode))
        assertTrue(server.poll(codes.deviceCode).body<AuthTokenResponse>().token.startsWith("osg_"))
    }

    private companion object {
        const val MAX_EVENTS = 50
    }
}
