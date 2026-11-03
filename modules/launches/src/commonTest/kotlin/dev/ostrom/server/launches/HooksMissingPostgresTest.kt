// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.presentation.AUDIT_READ_SCOPE
import dev.ostrom.server.identity.presentation.OrgAuditPageResponse
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.launches.domain.models.HooksCheck
import dev.ostrom.server.launches.domain.services.HooksCheckRules
import dev.ostrom.server.launches.infrastructure.OrgsHooksCheckRules
import dev.ostrom.server.launches.presentation.HooksCheckResponse
import dev.ostrom.server.launches.presentation.LaunchResponse
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
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
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `ostrom <agent>` and the hooks that never ran: the launch tells the CLI when to look and whether to stop the agent,
 * and the launch's own agent token records `hooks.missing`, which the organization's audit log then reads. Real
 * identity, orgs, gates and launches modules on PostgreSQL.
 */
class HooksMissingPostgresTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A person, their organization, a CLI token and a token that reads the audit log. */
    private class Person(val org: Org, val cli: String, val auditor: String)

    private suspend fun TestServer.person(login: String): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)) { false }
        suspend fun token(kind: TokenKind, scopes: Set<String>): String {
            val secret = (if (kind == TokenKind.CLI) "osc_" else "osp_") + Uuid.random().toHexString()
            val token = ApiToken(
                Uuid.random(), user.id, org.id, "cli", kind, scopes, "hint", null, now, now + 1.hours, null,
                null,
            )
            koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
            return secret
        }
        // A CLI token keeps only the CLI's scopes: the audit log is read with a personal token.
        return Person(org, token(TokenKind.CLI, CLI_SCOPES), token(TokenKind.PERSONAL, setOf(AUDIT_READ_SCOPE)))
    }

    private suspend fun TestServer.post(token: String?, path: String, body: String): HttpResponse =
        client.post(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun TestServer.launch(person: Person): LaunchResponse {
        val created = post(person.cli, "/api/v1/launches", LAUNCH)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return created.body()
    }

    private suspend fun TestServer.missing(token: String?, launchId: String, body: String = REPORT): HttpResponse =
        post(token, "/api/v1/launches/$launchId/hooks-missing", body)

    private suspend fun TestServer.recorded(person: Person): OrgAuditPageResponse =
        client.get("/api/v1/audit?event=hooks.missing") {
            header(HttpHeaders.Authorization, "Bearer ${person.auditor}")
        }.also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.body()

    private val enforcedGate = module {
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode? = null

                override suspend fun enforcesAny(orgId: Uuid, repo: String?): Boolean = true
            }
        }
    }

    @Test
    fun theLaunchTellsTheCliWhenToLookAndTheOrganizationsSettingChangesIt() =
        serverTest(launchModules()) { server ->
            val ada = server.person("ada")
            assertEquals(HooksCheckResponse(15, false), server.launch(ada).hooksCheck, "15 s, no gate to hold")

            server.koin.get<OrgsRepository>().update(ada.org.id, null, null, 45)
            assertEquals(HooksCheckResponse(45, false), server.launch(ada).hooksCheck)
            val other = server.person("bob")
            assertEquals(HooksCheckResponse(15, false), server.launch(other).hooksCheck, "Each organization's own")
        }

    @Test
    fun anEnforcedGateMakesTheCliStopAnAgentWithoutHooks() =
        serverTest(launchModules(), enforcedGate) { server ->
            val ada = server.person("ada")
            assertEquals(HooksCheckResponse(15, true), server.launch(ada).hooksCheck)
        }

    @Test
    fun theLaunchsOwnAgentTokenRecordsThatNoHookRanAndTheAuditLogReadsIt() =
        serverTest(launchModules()) { server ->
            val ada = server.person("ada")
            val launch = server.launch(ada)
            assertEquals(emptyList(), server.recorded(ada).items)

            val answered = server.missing(launch.agentToken, launch.launchId)
            assertEquals(HttpStatusCode.NoContent, answered.status, answered.bodyAsText())

            val line = server.recorded(ada).items.single()
            assertEquals("hooks.missing", line.event)
            assertEquals("launch:${launch.launchId}", line.subject)
            assertEquals(true, line.actor.startsWith("agent:"), line.actor)
            assertEquals(
                mapOf("agent" to "claude-code", "repo" to "acme/api", "waitedSeconds" to "15", "stopped" to "true"),
                line.meta.filterKeys { it in setOf("agent", "repo", "waitedSeconds", "stopped") },
            )
        }

    @Test
    fun noOtherCredentialRecordsAndABadWaitIsRefused() =
        serverTest(launchModules()) { server ->
            val ada = server.person("ada")
            val mallory = server.person("mallory")
            val first = server.launch(ada)
            val second = server.launch(ada)
            val elsewhere = server.launch(mallory)
            // Another launch's agent token, the person's CLI token, another organization's agent token: not found,
            // the same answer as a launch that does not exist.
            for (token in listOf(second.agentToken, ada.cli, elsewhere.agentToken)) {
                val refused = server.missing(token, first.launchId)
                assertEquals(HttpStatusCode.NotFound, refused.status, refused.bodyAsText())
            }
            assertEquals(HttpStatusCode.NotFound, server.missing(first.agentToken, "lch_unknown").status)
            assertEquals(HttpStatusCode.Unauthorized, server.missing(null, first.launchId).status)
            for (waited in listOf("-1", "3601")) {
                val body = """{"waitedSeconds":$waited,"stopped":true}"""
                val refused = server.missing(first.agentToken, first.launchId, body)
                assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
            }
            assertEquals(emptyList(), server.recorded(ada).items)
            assertEquals(emptyList(), server.recorded(mallory).items)
        }

    @Test
    fun theHooksCheckRulesReadTheOrganizationsDeadlineAndTheGatesEnforcement() =
        serverTest(launchModules()) { server ->
            val ada = server.person("ada")
            val orgs = server.koin.get<OrgsRepository>()
            val enforcing = object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode? = null

                override suspend fun enforcesAny(orgId: Uuid, repo: String?): Boolean = repo == "acme/api"
            }
            val rules: HooksCheckRules = OrgsHooksCheckRules(orgs, enforcing)

            assertEquals(HooksCheck(15, true), rules.check(ada.org.id, "acme/api"))
            assertEquals(HooksCheck(15, false), rules.check(ada.org.id, null))
            orgs.update(ada.org.id, null, null, 300)
            assertEquals(HooksCheck(300, true), rules.check(ada.org.id, "acme/api"))
            assertEquals(HooksCheck(15, false), rules.check(Uuid.random(), "x/y"), "An unknown organization: defaults")
        }

    private companion object {
        const val LAUNCH = """{"repo":"acme/api","agent":"claude-code","model":"claude-opus-4-1"}"""
        const val REPORT = """{"waitedSeconds":15,"stopped":true}"""
    }
}
