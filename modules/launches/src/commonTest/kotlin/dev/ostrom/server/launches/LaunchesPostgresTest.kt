// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.launches.domain.repositories.LaunchesRepository
import dev.ostrom.server.launches.infrastructure.PostgresLaunchesRepository
import dev.ostrom.server.launches.presentation.AgentPolicyResponse
import dev.ostrom.server.launches.presentation.LaunchResponse
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.runners.RunnersModule
import dev.ostrom.server.runners.domain.AgentKind
import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.DesiredState
import dev.ostrom.server.runners.domain.RepositorySpec
import dev.ostrom.server.runners.domain.RunnerTokens
import dev.ostrom.server.runners.domain.SessionLimits
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.sessions.presentation.SessionDto
import dev.ostrom.server.sessions.presentation.SessionResponse
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `ostrom claude` up to its first session, on the real identity, orgs, gates and sessions modules and PostgreSQL: the
 * CLI's token reads the policy and creates the launch, the agent token it gets opens and keeps the launch's session,
 * and nothing crosses a launch or an organization.
 */
class LaunchesPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, RunnersModule,
        LaunchesModule,
    )
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A person, a member of a new organization, and a CLI token of theirs bound to it. */
    private class Person(val user: User, val org: Org, val memberId: Uuid, val cliToken: String)

    private suspend fun TestServer.person(login: String): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        // The CLI's own token, bound to the organization: a CLI token names none, and resolving the person's only
        // organization comes with the `Ostrom-Org` resolution (#64); the launch reads whichever the orgs module gives.
        val secret = "osc_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), user.id, org.id, "cli", TokenKind.CLI, CLI_SCOPES, "hint", null, now, now + 1.hours, null,
            null,
        )
        koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
        return Person(user, org, member.id, secret)
    }

    private suspend fun TestServer.call(token: String?, path: String, body: String? = null): HttpResponse =
        if (body == null) {
            client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        } else {
            client.post(path) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun TestServer.launch(person: Person, body: String = LAUNCH): LaunchResponse {
        val created = call(person.cliToken, "/api/v1/launches", body)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return created.body()
    }

    @Test
    fun theCliReadsThePolicyLaunchesAndItsAgentTokenRunsTheLaunchsSession() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val policy = server.call(ada.cliToken, "/api/v1/policy/agents?agent=claude-code&dev=mallory&model=opus")
            assertEquals(AgentPolicyResponse(allowed = true), policy.body<AgentPolicyResponse>())

            val launch = server.launch(ada)
            assertTrue(launch.launchId.startsWith("lch_"), launch.launchId)
            assertTrue(launch.agentToken.startsWith("osa_"), "An agent token, never the CLI's own")
            val expiresAt = Instant.parse(launch.expiresAt)
            assertTrue(expiresAt > now + 23.hours && expiresAt <= Clock.System.now() + 24.hours, launch.expiresAt)
            val stored = server.koin.get<LaunchesRepository>().find(ada.org.id, launch.launchId)
            assertEquals(
                listOf("ada", "acme/api", "claude-code", "claude-opus-4-1"),
                listOf(stored?.dev, stored?.repo, stored?.agent, stored?.model),
            )
            assertEquals(ada.user.id, stored?.userId)
            assertNull(stored?.cloudSessionId, "A launch of the CLI is no cloud session's")

            val started = server.call(launch.agentToken, "/api/v1/sessions", SESSION)
            assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
            val session = started.body<SessionResponse>().session
            assertEquals("ada", session.dev, "The person is the launch's, never the body's")
            assertEquals(launch.launchId, session.launchId)
            val alive = server.call(launch.agentToken, "/api/v1/sessions/s-1/alive", "{}")
            assertEquals(false, alive.body<SessionDto>().ended)
            val read = server.call(ada.cliToken, "/api/v1/sessions/s-1")
            assertEquals(HttpStatusCode.OK, read.status, "The person reads the session of their launch")
        }

    @Test
    fun anAgentTokenActsOnItsOwnLaunchOnlyAndNeverLaunches() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val first = server.launch(ada)
            val second = server.launch(ada)
            assertTrue(first.launchId != second.launchId)
            assertEquals(HttpStatusCode.OK, server.call(first.agentToken, "/api/v1/sessions", SESSION).status)

            val foreign = server.call(second.agentToken, "/api/v1/sessions/s-1/alive", "{}")
            assertEquals(HttpStatusCode.Forbidden, foreign.status, "Another launch's session: ${foreign.bodyAsText()}")
            assertEquals(HttpStatusCode.Forbidden, server.call(first.agentToken, "/api/v1/launches", LAUNCH).status)
            assertEquals(
                HttpStatusCode.Forbidden,
                server.call(first.agentToken, "/api/v1/policy/agents?agent=codex").status,
            )
        }

    @Test
    fun withoutACredentialOrAnOrganizationNothingIsLaunched() =
        serverTest(modules) { server ->
            assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/launches", LAUNCH).status)
            assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/policy/agents?agent=codex").status)
            assertEquals(HttpStatusCode.Unauthorized, server.call("osc_unknown", "/api/v1/launches", LAUNCH).status)

            // A person whose organization is gone (or who left it) is a member of nothing, and their agents stop.
            val ada = server.person("ada")
            val running = server.launch(ada)
            assertEquals(HttpStatusCode.OK, server.call(running.agentToken, "/api/v1/sessions", SESSION).status)
            server.koin.get<OrgsRepository>().delete(ada.org.id, now)
            val stopped = server.call(running.agentToken, "/api/v1/sessions/s-1/alive", "{}")
            assertEquals(HttpStatusCode.Forbidden, stopped.status, stopped.bodyAsText())
            val orphan = server.call(ada.cliToken, "/api/v1/launches", LAUNCH)
            assertEquals(HttpStatusCode.NotFound, orphan.status, orphan.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, server.call(ada.cliToken, "/api/v1/policy/agents").status)
            assertEquals(1, server.launchesSeenBy(ada.org.id), "Only the launch made while a member")
        }

    @Test
    fun thePolicyRefusesAMalformedLaunchAndNothingIsStored() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val verdict = server.call(ada.cliToken, "/api/v1/policy/agents?agent=Claude%20Code")
            val refusal = AgentPolicyResponse(false, "The agent name is not valid.")
            assertEquals(refusal, verdict.body<AgentPolicyResponse>())
            val refused = server.call(ada.cliToken, "/api/v1/launches", """{"agent":"Claude Code"}""")
            assertEquals(HttpStatusCode.BadRequest, refused.status, "A malformed name is the client's mistake")
            assertTrue("The agent name is not valid." in refused.bodyAsText(), refused.bodyAsText())
            val malformed = server.call(ada.cliToken, "/api/v1/launches", """{"repo":"","agent":"codex"}""")
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals(HttpStatusCode.BadRequest, server.call(ada.cliToken, "/api/v1/launches", "{}").status)
            assertEquals(0, server.launchesSeenBy(ada.org.id))
            // A launch may name only its agent.
            val agentOnly = server.call(ada.cliToken, "/api/v1/launches", """{"agent":"pi"}""")
            assertEquals(HttpStatusCode.Created, agentOnly.status)
        }

    @Test
    fun anotherOrganizationNeitherSeesNorUsesALaunch() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val bob = server.person("bob")
            val adas = server.launch(ada)
            assertEquals(HttpStatusCode.OK, server.call(adas.agentToken, "/api/v1/sessions", SESSION).status)
            val bobs = server.launch(bob)

            val launches = server.koin.get<LaunchesRepository>()
            assertNull(launches.find(bob.org.id, adas.launchId), "Read in Bob's organization, Ada's launch is absent")
            assertFalse(bobs.launchId == adas.launchId)
            val across = server.call(bobs.agentToken, "/api/v1/sessions/s-1/alive", "{}")
            assertEquals(HttpStatusCode.NotFound, across.status, "Bob's agent never sees Ada's session")
            val read = server.call(bob.cliToken, "/api/v1/sessions/s-1")
            assertEquals(HttpStatusCode.NotFound, read.status)

            // The database itself: without its organization set, a transaction reads no launch and writes none. The
            // test server's driver is the serving role, as in production: row level security applies to it.
            val seen = server.driver.transaction {
                val none = fetchAll("SELECT COUNT(*) AS n FROM launches_launches").getOrThrow()
                    .first().get("n").asString()
                val written = execute("UPDATE launches_launches SET dev = 'mallory'").getOrThrow()
                none to written
            }
            assertEquals("0" to 0L, seen)
            assertEquals(1, server.launchesSeenBy(ada.org.id))
            assertEquals("ada", launches.find(ada.org.id, adas.launchId)?.dev)
        }

    @Test
    fun aLaunchIsReadByItsPrimaryKey() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            repeat(LAUNCHES) { server.launch(ada) }
            server.database.owner.execute("ANALYZE launches_launches").getOrThrow() // the serving role owns no table
            val plan = server.driver.withOrganization(ada.org.id) { tx ->
                val explain = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${PostgresLaunchesRepository.FIND}")
                    .bind("org_id", ada.org.id).bind("id", "lch_absent")
                tx.execute("SET LOCAL enable_seqscan = off").getOrThrow()
                tx.fetchAll(explain).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
            }
            println("-- a launch by its id (each agent request)\n$plan")
            assertTrue("launches_launches_pkey" in plan, plan)
        }

    /** A cloud session of [owner]'s, its execution 2 running, and the runner token of that execution. */
    private suspend fun TestServer.cloudSession(
        owner: Person,
        agent: AgentKind = AgentKind.CLAUDE,
        ownerId: String = owner.memberId.toString(),
    ): Pair<CloudSession, String> {
        val tokens = koin.get<RunnerTokens>()
        val token = tokens.mint()
        val session = CloudSession(
            id = Uuid.random(), orgId = owner.org.id, ownerId = ownerId, name = "task", agent = agent,
            repository = RepositorySpec("https://github.com/acme/api", null), state = SessionState.CREATING,
            desiredState = DesiredState.RUNNING, phase = null, epoch = 2, generation = 0, failure = null,
            limits = SessionLimits(IDLE_MINUTES, RUN_MINUTES, false), createdAt = now, updatedAt = now,
            executionStartedAt = now, runnerTokenHash = tokens.hash(token), model = "claude-opus-4-1",
        )
        assertTrue(koin.get<CloudSessionsRepository>().createWithinCap(session, RUNNING_CAP))
        return session to token
    }

    private suspend fun TestServer.runnerLaunch(token: String?, body: String): HttpResponse =
        client.post("/api/v1/cloud/runner/launch") {
            token?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun claim(session: CloudSession, epoch: Long = session.epoch) =
        """{"sessionId":"${session.id}","epoch":$epoch}"""

    @Test
    fun aCloudSessionsRunnerOpensItsLaunchForItsOwnerWithItsOwnToken() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val (session, token) = server.cloudSession(ada)
            val opened = server.runnerLaunch("Bearer $token", claim(session))
            assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
            val launch = opened.body<LaunchResponse>()
            val stored = server.koin.get<LaunchesRepository>().find(ada.org.id, launch.launchId)
            assertEquals(
                listOf("ada", "https://github.com/acme/api", "claude-code", "claude-opus-4-1"),
                listOf(stored?.dev, stored?.repo, stored?.agent, stored?.model),
            )
            assertEquals(session.id, stored?.cloudSessionId)
            val started = server.call(launch.agentToken, "/api/v1/sessions", SESSION)
            assertEquals(HttpStatusCode.OK, started.status, "The agent in the pod runs the launch's session")
            assertEquals(launch.launchId, started.body<SessionResponse>().session.launchId)
        }

    @Test
    fun theRunnerChannelRefusesAnyOtherCredentialOrClaim() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val (session, token) = server.cloudSession(ada)
            assertEquals(HttpStatusCode.Unauthorized, server.runnerLaunch(null, claim(session)).status)
            assertEquals(HttpStatusCode.Unauthorized, server.runnerLaunch(token, claim(session)).status, "Not Bearer")
            assertEquals(HttpStatusCode.Unauthorized, server.runnerLaunch("Bearer ", claim(session)).status)
            val member = server.runnerLaunch("Bearer ${ada.cliToken}", claim(session))
            assertEquals(HttpStatusCode.Unauthorized, member.status, "A member's token is not a runner token")
            // S2-46: an unknown token never makes the server read the body.
            assertEquals(HttpStatusCode.Unauthorized, server.runnerLaunch("Bearer ostrom_runner_x", "{not json").status)
            assertEquals(HttpStatusCode.BadRequest, server.runnerLaunch("Bearer $token", "{not json").status)
            val malformed = server.runnerLaunch("Bearer $token", """{"sessionId":"nope","epoch":2}""")
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals(HttpStatusCode.Conflict, server.runnerLaunch("Bearer $token", claim(session, 1)).status)
            val (shell, shellToken) = server.cloudSession(ada, AgentKind.SHELL)
            assertEquals(HttpStatusCode.BadRequest, server.runnerLaunch("Bearer $shellToken", claim(shell)).status)
            assertEquals(0, server.launchesSeenBy(ada.org.id), "Nothing opened")
        }

    @Test
    fun aRunnerOpensNoLaunchForAnotherOrganizationsMember() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val bob = server.person("bob")
            // A session of Bob's organization naming Ada's member: the owner is read in Bob's organization only.
            val (session, token) = server.cloudSession(bob, ownerId = ada.memberId.toString())
            assertEquals(HttpStatusCode.NotFound, server.runnerLaunch("Bearer $token", claim(session)).status)
            assertEquals(0 to 0, server.launchesSeenBy(ada.org.id) to server.launchesSeenBy(bob.org.id))
        }

    // Read in the organization, as the serving role: row level security keeps the other organizations out.
    private suspend fun TestServer.launchesSeenBy(orgId: Uuid): Int = driver.withOrganization(orgId) { tx ->
        val count = Statement.create("SELECT COUNT(*) AS n FROM launches_launches WHERE org_id = :org_id")
            .bind("org_id", orgId)
        tx.fetchAll(count).getOrThrow().first().get("n").asString().toInt()
    }

    private companion object {
        const val LAUNCHES = 20
        const val IDLE_MINUTES = 30
        const val RUN_MINUTES = 120
        const val RUNNING_CAP = 5
        const val LAUNCH = """{"repo":"acme/api","agent":"claude-code","model":"claude-opus-4-1"}"""
        const val SESSION = """{"id":"s-1","agent":"claude-code","repo":"acme/api","dev":"mallory"}"""
    }
}
