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
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, LaunchesModule,
    )
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A person, a member of a new organization, and a CLI token of theirs bound to it. */
    private class Person(val user: User, val org: Org, val cliToken: String)

    private suspend fun TestServer.person(login: String): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)) { false }
        // The CLI's own token, bound to the organization: a CLI token names none, and resolving the person's only
        // organization comes with the `Ostrom-Org` resolution (#64); the launch reads whichever the orgs module gives.
        val secret = "osc_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), user.id, org.id, "cli", TokenKind.CLI, CLI_SCOPES, "hint", null, now, now + 1.hours, null,
            null,
        )
        koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
        return Person(user, org, secret)
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

            // A person whose organization is gone (or who left it) is a member of nothing.
            val ada = server.person("ada")
            server.koin.get<OrgsRepository>().delete(ada.org.id, now)
            val orphan = server.call(ada.cliToken, "/api/v1/launches", LAUNCH)
            assertEquals(HttpStatusCode.NotFound, orphan.status, orphan.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, server.call(ada.cliToken, "/api/v1/policy/agents").status)
            assertEquals(0, server.launchesSeenBy(ada.org.id))
        }

    @Test
    fun thePolicyRefusesAMalformedLaunchAndNothingIsStored() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val verdict = server.call(ada.cliToken, "/api/v1/policy/agents?agent=Claude%20Code")
            val refusal = AgentPolicyResponse(false, "The agent name is not valid.")
            assertEquals(refusal, verdict.body<AgentPolicyResponse>())
            val refused = server.call(ada.cliToken, "/api/v1/launches", """{"agent":"Claude Code"}""")
            assertEquals(HttpStatusCode.Forbidden, refused.status)
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

            // The database itself: without its organization set, a transaction reads no launch and writes none.
            server.driver.execute(PROBE_ROLE).getOrThrow()
            server.driver.execute("GRANT SELECT, UPDATE ON launches_launches TO ostrom_rls_probe").getOrThrow()
            val seen = server.driver.transaction {
                execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
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
            server.driver.execute("ANALYZE launches_launches").getOrThrow()
            val plan = server.driver.withOrganization(ada.org.id) { tx ->
                val explain = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${PostgresLaunchesRepository.FIND}")
                    .bind("org_id", ada.org.id).bind("id", "lch_absent")
                tx.execute("SET LOCAL enable_seqscan = off").getOrThrow()
                tx.fetchAll(explain).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
            }
            println("-- a launch by its id (each agent request)\n$plan")
            assertTrue("launches_launches_pkey" in plan, plan)
        }

    private suspend fun TestServer.launchesSeenBy(orgId: Uuid): Int = driver.withOrganization(orgId) { tx ->
        tx.fetchAll("SELECT COUNT(*) AS n FROM launches_launches").getOrThrow().first().get("n").asString().toInt()
    }

    private companion object {
        const val LAUNCHES = 20
        const val PROBE_ROLE =
            "DO \$\$ BEGIN CREATE ROLE ostrom_rls_probe NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$"
        const val LAUNCH = """{"repo":"acme/api","agent":"claude-code","model":"claude-opus-4-1"}"""
        const val SESSION = """{"id":"s-1","agent":"claude-code","repo":"acme/api","dev":"mallory"}"""
    }
}
