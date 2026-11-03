// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.mcprelay.domain.McpCall
import dev.ostrom.server.mcprelay.domain.McpOperation
import dev.ostrom.server.mcprelay.domain.McpOutcome
import dev.ostrom.server.mcprelay.domain.McpRelayRepository
import dev.ostrom.server.mcprelay.domain.McpTransport
import dev.ostrom.server.mcprelay.domain.StdioLauncher
import dev.ostrom.server.mcprelay.domain.StdioSpec
import dev.ostrom.server.mcprelay.presentation.ArgumentConditionDto
import dev.ostrom.server.mcprelay.presentation.ArgumentPoliciesResponse
import dev.ostrom.server.mcprelay.presentation.ArgumentPolicyRequest
import dev.ostrom.server.mcprelay.presentation.ArgumentPolicyResponse
import dev.ostrom.server.mcprelay.presentation.McpAssignmentRequest
import dev.ostrom.server.mcprelay.presentation.McpAssignmentResponse
import dev.ostrom.server.mcprelay.presentation.McpAssignmentsPage
import dev.ostrom.server.mcprelay.presentation.McpCallsPage
import dev.ostrom.server.mcprelay.presentation.McpServerDto
import dev.ostrom.server.mcprelay.presentation.McpSettingsDto
import dev.ostrom.server.mcprelay.presentation.McpUsageResponse
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.presentation.SecretWriteRequest
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Assignments, policies, settings and the journal managed over HTTP, and the repository on its own. */
class McpRelayManagementPostgresTest : RelayPostgresHarness() {
    @Test
    fun changingAnMcpServerFromTheConsoleNeedsASignInOfLessThanFiveMinutes() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val sentry = McpServerDto("sentry", "https://sentry.example.com/mcp", "http")
                val path = "$admin/servers/sentry"
                val stale = listOf(
                    sendJson(HttpMethod.Post, "$admin/servers", sentry, "stale-admin-a"),
                    sendJson(HttpMethod.Put, path, sentry, "stale-admin-a"),
                    client.delete(path) { bearerAuth("stale-admin-a") },
                )
                for (refused in stale) {
                    assertEquals(HttpStatusCode.Forbidden, refused.status)
                    assertTrue("step_up_required" in refused.bodyAsText(), refused.bodyAsText())
                }
                assertEquals(HttpStatusCode.Created, sendJson(HttpMethod.Post, "$admin/servers", sentry).status)
                assertEquals(HttpStatusCode.OK, sendJson(HttpMethod.Put, path, sentry).status)
                // Who may use a server, its argument policies and the agents' prefix are the server's too.
                val assignment = "$path/assignments/${Relay.memberA.memberId}"
                val policy = ArgumentPolicyRequest("query", emptyList(), "deny", "No.")
                val alsoStale = listOf(
                    sendJson(HttpMethod.Put, assignment, McpAssignmentRequest(listOf("send")), "stale-admin-a"),
                    client.delete(assignment) { bearerAuth("stale-admin-a") },
                    sendJson(HttpMethod.Post, "$path/policies", policy, "stale-admin-a"),
                    client.delete("$path/policies/${Uuid.random()}") { bearerAuth("stale-admin-a") },
                    sendJson(HttpMethod.Put, "$admin/settings", McpSettingsDto("evil"), "stale-admin-a"),
                )
                for (refused in alsoStale) {
                    assertEquals(HttpStatusCode.Forbidden, refused.status)
                    assertTrue("step_up_required" in refused.bodyAsText(), refused.bodyAsText())
                }
                assertEquals(McpSettingsDto("ostrom"), client.get("$admin/settings") { bearerAuth("admin-a") }.body())
                assertEquals(HttpStatusCode.NoContent, client.delete(path) { bearerAuth("admin-a") }.status)
            }
        }

    @Test
    fun assignmentsCarryAMembersOwnCredentialAndAreRemoved() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val sentry = McpServerDto("sentry", "https://sentry.example.com/mcp", "http", scope = "per-member")
                assertEquals(HttpStatusCode.Created, sendJson(HttpMethod.Post, "$admin/servers", sentry).status)
                val mine = sendJson(
                    HttpMethod.Post,
                    "/api/v1/secrets",
                    SecretWriteRequest("MINE", "member_own_token", "personal"),
                    "member-a",
                )
                assertEquals(HttpStatusCode.Created, mine.status, mine.bodyAsText())
                val own = "$admin/servers/sentry/assignments/${Relay.memberA.memberId}"
                val unknown = sendJson(HttpMethod.Put, own, McpAssignmentRequest(credentialSecret = "NOPE"))
                assertEquals(HttpStatusCode.BadRequest, unknown.status, "An unknown personal secret")
                val assigned = sendJson(HttpMethod.Put, own, McpAssignmentRequest(listOf("send"), "MINE"))
                assertEquals("MINE", assigned.body<McpAssignmentResponse>().credentialSecret)
                assign(McpServerDto("sentry", transport = "http"), "all", null)
                val listed = client.get("$admin/servers/sentry/assignments") { bearerAuth("admin-a") }
                    .body<McpAssignmentsPage>().items
                assertEquals(listOf(null, "MINE"), listed.map { it.credentialSecret }, "The org's first, then members'")
                for (member in listOf(Relay.memberA.memberId.toString(), "all")) {
                    val path = "$admin/servers/sentry/assignments/$member"
                    assertEquals(HttpStatusCode.NoContent, client.delete(path) { bearerAuth("admin-a") }.status)
                    assertEquals(HttpStatusCode.NotFound, client.delete(path) { bearerAuth("admin-a") }.status)
                }
                val badId = client.delete("$admin/servers/sentry/assignments/nope") { bearerAuth("admin-a") }
                assertEquals(HttpStatusCode.NotFound, badId.status)
            }
        }

    @Test
    fun policiesSettingsAndTheJournalAreReadAndChangedOverHttp() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                grafana()
                val policies = "$admin/servers/grafana/policies"
                val prod =
                    ArgumentPolicyRequest(
                        "query",
                        listOf(ArgumentConditionDto("db", "matches", "^prod")),
                        "deny",
                        "No.",
                    )
                val created = sendJson(HttpMethod.Post, policies, prod)
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val id = created.body<ArgumentPolicyResponse>().id
                val broken = prod.copy(conditions = listOf(ArgumentConditionDto("db", "matches", "(")))
                assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Post, policies, broken).status)
                val listed = client.get(policies) { bearerAuth("admin-a") }.body<ArgumentPoliciesResponse>()
                assertEquals(listOf("MATCHES"), listed.policies.single().conditions.map { it.operator })
                assertEquals(HttpStatusCode.NoContent, client.delete("$policies/$id") { bearerAuth("admin-a") }.status)
                assertEquals(HttpStatusCode.NotFound, client.delete("$policies/$id") { bearerAuth("admin-a") }.status)
                assertEquals(HttpStatusCode.NotFound, client.delete("$policies/nope") { bearerAuth("admin-a") }.status)

                val settings = client.get("$admin/settings") { bearerAuth("member-a") }.body<McpSettingsDto>()
                assertEquals(McpSettingsDto("ostrom"), settings)
                val before = client.get("$admin/calls?before=2030-01-01T00:00:00Z") { bearerAuth("admin-a") }
                assertEquals(emptyList(), before.body<McpCallsPage>().items)
                // The last two are instants the parser reads but PostgreSQL cannot store: a 400, not a 500.
                val beyond = listOf("%2B300000-01-01T00:00:00Z", "-300000-01-01T00:00:00Z")
                for (cursor in listOf("yesterday", "2030-01-01T00:00:00Z/nope") + beyond) {
                    assertEquals(
                        HttpStatusCode.BadRequest,
                        client.get("$admin/calls?before=$cursor") { bearerAuth("admin-a") }.status,
                        cursor,
                    )
                }
                val usage = client.get("$admin/usage?days=7") { bearerAuth("admin-a") }.body<McpUsageResponse>()
                assertEquals(emptyMap(), usage.byServer)
            }
        }

    @Test
    fun journalPagesNeitherLoseNorRepeatCallsOfTheSameInstant() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                // Three calls in the same microsecond: a page boundary between them loses or repeats none.
                val journal = koin.get<McpRelayRepository>()
                val tied = List(3) {
                    McpCall(
                        Uuid.random(), Relay.adminA.orgId, Relay.adminA.memberId, Uuid.random(), "grafana",
                        McpOperation.CALL, "query", McpOutcome.OK, Instant.parse("2026-10-30T05:00:00Z"), 1, 1,
                        launchId = "lch_$it".takeIf { _ -> it > 0 },
                    )
                }
                tied.forEach { journal.record(it) }
                val first = client.get("$admin/calls?limit=2") { bearerAuth("admin-a") }.body<McpCallsPage>()
                val next = assertNotNull(first.next)
                val second = client.get("$admin/calls?limit=2&before=$next") { bearerAuth("admin-a") }
                    .body<McpCallsPage>()
                assertEquals(tied.map { it.id.toString() }.toSet(), (first.items + second.items).map { it.id }.toSet())
                assertEquals(3, (first.items + second.items).size)
                assertNull(second.next)
                assertEquals(
                    tied.associate { it.id.toString() to it.launchId },
                    (first.items + second.items).associate { it.id to it.launchId },
                    "The journal keeps the launch of an agent token's call, and none for a member's own",
                )
            }
        }

    @Test
    fun theRepositoryStoresAStdioCommandAndMissesWhatIsNotThere() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            val repository = server.koin.get<McpRelayRepository>()
            val stdio = Relay.server("mysql", transport = McpTransport.STDIO).copy(
                stdio = StdioSpec(
                    StdioLauncher.PYPI,
                    "mcp-mysql==1.2.0",
                    listOf("--read-only"),
                    mapOf("MYSQL_MODE" to "ro"),
                    mapOf("MYSQL_DSN" to Uuid.random()),
                ),
            )
            assertTrue(repository.createServer(stdio))
            assertEquals(stdio, repository.getServer(Relay.orgA, stdio.id))
            assertNull(repository.getServer(Relay.orgA, Uuid.random()))
            assertNull(repository.getServer(Relay.orgB, stdio.id), "Another org misses it")
            assertNull(repository.updateServer(stdio.copy(id = Uuid.random())))
            assertFalse(repository.deleteServer(Relay.orgB, stdio.id))
        }
}
