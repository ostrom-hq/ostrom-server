// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.launches.presentation.LaunchLimitsBody
import run.ostrom.server.launches.presentation.LaunchResponse
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.secrets.domain.LaunchCaps
import run.ostrom.server.sessions.presentation.SessionResponse
import run.ostrom.server.sessions.presentation.SessionsPage
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The labels of a launch and its limits, on the real identity, orgs, gates and sessions modules and PostgreSQL: the
 * CLI names labels at launch, the sessions of the launch carry them and are listed by them, and an administrator sets
 * the launch's limits, which its agent token's calls then carry.
 */
class LaunchLimitsPostgresTest {
    private val modules = launchModules()
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private class Person(val orgId: Uuid, val memberId: Uuid, val cliToken: String)

    private suspend fun TestServer.joinsNew(
        login: String,
        org: Org?,
        role: OrgRole,
        invitedBy: Uuid?,
    ): Pair<Person, Org> {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val team = org ?: Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), team.id, user.id, role, now)
        if (org == null) {
            koin.get<OrgsRepository>().create(team, member) { false }
        } else {
            val expires = now + 1.hours
            val inviter = requireNotNull(invitedBy)
            val invitation = Invitation(Uuid.random(), team.id, null, role, inviter, now, expires, null, 0, null)
            val invitations = koin.get<InvitationsRepository>()
            invitations.create(invitation, "hash-" + Uuid.random().toHexString())
            invitations.accept(invitation, member, now)
        }
        val secret = "osc_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), user.id, team.id, "cli", TokenKind.CLI, CLI_SCOPES, "hint", null, now, now + 1.hours, null,
            null,
        )
        koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
        return Person(team.id, member.id, secret) to team
    }

    private suspend fun TestServer.send(
        token: String,
        path: String,
        body: String? = null,
        put: Boolean = false,
    ): HttpResponse =
        when {
            put -> client.put(path) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            body == null -> client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
            else -> client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    @Test
    fun theLabelsOfALaunchReachItsSessionsAndFilterTheListAndABadOneIsRefused() = serverTest(modules) { server ->
        val (ada, _) = server.joinsNew("ada", null, OrgRole.OWNER, null)
        val body = """{"repo":"acme/api","agent":"claude-code","labels":{"task":"1234","team":"payments"}}"""
        val launch = server.send(ada.cliToken, "/api/v1/launches", body).body<LaunchResponse>()
        assertEquals(
            mapOf("task" to "1234", "team" to "payments"),
            server.koin.get<LaunchesRepository>().find(ada.orgId, launch.launchId)?.labels,
        )
        val session = """{"id":"s-1","agent":"claude-code","repo":"acme/api"}"""
        val started = server.send(launch.agentToken, "/api/v1/sessions", session).body<SessionResponse>().session
        assertEquals(mapOf("task" to "1234", "team" to "payments"), started.labels)
        val other = server.send(ada.cliToken, "/api/v1/launches", """{"agent":"codex"}""").body<LaunchResponse>()
        server.send(other.agentToken, "/api/v1/sessions", """{"id":"s-2","agent":"codex"}""")

        suspend fun ids(query: String) =
            server.send(ada.cliToken, "/api/v1/sessions$query").body<SessionsPage>().items.map { it.id }
        assertEquals(listOf("s-1"), ids("?label=task=1234"))
        assertEquals(listOf("s-1"), ids("?label=task=1234&label=team=payments"))
        assertEquals(emptyList(), ids("?label=task=999"))
        assertEquals(setOf("s-1", "s-2"), ids("").toSet())

        val refused = server.send(ada.cliToken, "/api/v1/launches", """{"agent":"codex","labels":{"Task":"1"}}""")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue("invalid_label" in refused.bodyAsText(), refused.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, server.send(ada.cliToken, "/api/v1/sessions?label=task").status)
    }

    @Test
    fun anAdminSetsTheLimitsOfALaunchAndOnlyAnAdminOrItsPersonReadsThem() = serverTest(modules) { server ->
        val (ada, org) = server.joinsNew("ada", null, OrgRole.OWNER, null)
        val (bob, _) = server.joinsNew("bob", org, OrgRole.MEMBER, ada.memberId)
        val (cy, _) = server.joinsNew("cy", org, OrgRole.MEMBER, ada.memberId)
        val launch = server.send(bob.cliToken, "/api/v1/launches", """{"agent":"codex"}""").body<LaunchResponse>()
        val path = "/api/v1/launches/${launch.launchId}/limits"
        assertEquals(LaunchLimitsBody(0, 0, false, 0), server.send(ada.cliToken, path).body(), "None until set")

        val set = server.send(ada.cliToken, path, """{"budgetMicros":5000000,"maxConcurrent":2,"paused":true}""", true)
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        assertEquals(LaunchLimitsBody(5_000_000, 2, true, 0), set.body())
        assertEquals(
            LaunchCaps(5_000_000, 2, true),
            server.koin.get<LaunchesRepository>().find(org.id, launch.launchId)?.caps,
        )
        val read = server.send(bob.cliToken, path).body<LaunchLimitsBody>()
        assertEquals(LaunchLimitsBody(5_000_000, 2, true, 0), read, "Its person reads")
        assertEquals(HttpStatusCode.NotFound, server.send(cy.cliToken, path).status, "Another member does not")

        val own = server.send(bob.cliToken, path, """{"budgetMicros":0,"maxConcurrent":0,"paused":false}""", true)
        assertEquals(HttpStatusCode.NotFound, own.status, "A member never lifts a limit")
        val tooMany = server.send(ada.cliToken, path, """{"budgetMicros":0,"maxConcurrent":33,"paused":false}""", true)
        assertEquals(HttpStatusCode.BadRequest, tooMany.status)
        assertTrue("invalid_launch_limits" in tooMany.bodyAsText(), tooMany.bodyAsText())
        val unlimited = """{"budgetMicros":0,"maxConcurrent":0,"paused":false}"""
        val agent = server.send(launch.agentToken, path, unlimited, true)
        assertTrue(agent.status.value in listOf(401, 403, 404), "An agent token never sets limits: ${agent.status}")
        assertEquals(
            HttpStatusCode.NotFound,
            server.send(ada.cliToken, "/api/v1/launches/lch_none/limits").status,
        )
    }
}
