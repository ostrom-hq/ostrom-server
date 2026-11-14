// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.domain.McpCall
import run.ostrom.server.mcprelay.domain.McpOperation
import run.ostrom.server.mcprelay.domain.McpOutcome
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.presentation.ArgumentConditionDto
import run.ostrom.server.mcprelay.presentation.ArgumentPoliciesResponse
import run.ostrom.server.mcprelay.presentation.ArgumentPolicyRequest
import run.ostrom.server.mcprelay.presentation.ArgumentPolicyResponse
import run.ostrom.server.mcprelay.presentation.McpAssignmentRequest
import run.ostrom.server.mcprelay.presentation.McpAssignmentsPage
import run.ostrom.server.mcprelay.presentation.McpServerDto
import run.ostrom.server.mcprelay.presentation.McpServersPage
import run.ostrom.server.mcprelay.presentation.McpSettingsDto
import run.ostrom.server.mcprelay.presentation.McpUsageResponse
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Another organization's owner (admin-b) neither reads nor changes the assignments, argument policies, settings and
 * usage of org A's MCP servers: every route on one of A's servers answers 404, B's settings and usage are B's own,
 * putting a server of the same name creates B's own, and org A reads everything unchanged.
 */
class McpRelayIsolationPostgresTest : RelayPostgresHarness() {
    private suspend fun TestServer.read(path: String, token: String = "admin-a"): HttpResponse =
        client.get(path) { bearerAuth(token) }

    @Test
    @Suppress("LongMethod") // Every route on one server of org A, then the owner's control, read top to bottom.
    fun anotherOrgNeitherReadsNorChangesTheAssignmentsPoliciesAndUsageOfAServer() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val grafana = grafana()
                assign(grafana, "all", listOf("query_loki_logs"))
                assign(grafana, Relay.memberA.memberId.toString(), null)
                val policies = "$admin/servers/grafana/policies"
                val prodOnly = listOf(ArgumentConditionDto("db", "matches", "^prod"))
                val prod = ArgumentPolicyRequest("*", prodOnly, "deny", "No.")
                val policy = sendJson(HttpMethod.Post, policies, prod)
                assertEquals(HttpStatusCode.Created, policy.status, policy.bodyAsText())
                val policyId = policy.body<ArgumentPolicyResponse>().id
                koin.get<McpRelayRepository>().record(
                    McpCall(
                        Uuid.random(), Relay.orgA, Relay.memberA.memberId, Uuid.random(), "grafana",
                        McpOperation.CALL, "query_loki_logs", McpOutcome.OK, Clock.System.now() - 1.minutes, 1, 1,
                    ),
                )
                val assignments = "$admin/servers/grafana/assignments"
                val assignmentsBefore = read(assignments).body<McpAssignmentsPage>()
                val policiesBefore = read(policies).body<ArgumentPoliciesResponse>()
                val serversBefore = read("$admin/servers").body<McpServersPage>()

                val attempts = listOf(
                    read(assignments, "admin-b"),
                    sendJson(HttpMethod.Put, "$assignments/all", McpAssignmentRequest(null), "admin-b"),
                    sendJson(
                        HttpMethod.Put,
                        "$assignments/${Relay.adminB.memberId}",
                        McpAssignmentRequest(listOf("update_dashboard")),
                        "admin-b",
                    ),
                    client.delete("$assignments/all") { bearerAuth("admin-b") },
                    client.delete("$assignments/${Relay.memberA.memberId}") { bearerAuth("admin-b") },
                    read(policies, "admin-b"),
                    sendJson(HttpMethod.Post, policies, prod.copy(message = "Mine."), "admin-b"),
                    client.delete("$policies/$policyId") { bearerAuth("admin-b") },
                )
                attempts.forEach { response ->
                    assertEquals(
                        HttpStatusCode.NotFound,
                        response.status,
                        "${response.call.request.method.value} ${response.call.request.url}",
                    )
                }
                val usageOfB = read("$admin/usage?days=7", "admin-b").body<McpUsageResponse>()
                assertEquals(emptyMap(), usageOfB.byServer, "B's usage counts none of A's calls")
                assertEquals(emptyList(), usageOfB.rows)

                // A PUT of the same name in B creates B's own server, and leaves A's alone.
                val ownOfB = McpServerDto("grafana", "https://mcp.grafana.example.com/other", "http")
                val put = sendJson(HttpMethod.Put, "$admin/servers/grafana", ownOfB, "admin-b")
                assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
                val listedForB = read("$admin/servers", "admin-b").body<McpServersPage>().items
                assertEquals(listOf("https://mcp.grafana.example.com/other"), listedForB.map { it.url })
                assertEquals(emptyList(), read(assignments, "admin-b").body<McpAssignmentsPage>().items)

                assertEquals(serversBefore, read("$admin/servers").body<McpServersPage>(), "A's server unchanged")
                assertEquals(assignmentsBefore, read(assignments).body<McpAssignmentsPage>(), "A's assignments")
                assertEquals(policiesBefore, read(policies).body<ArgumentPoliciesResponse>(), "A's policies")
                val usageOfA = read("$admin/usage?days=7").body<McpUsageResponse>()
                assertEquals(mapOf("grafana" to 1L), usageOfA.byServer)
            }
        }

    @Test
    fun anotherOrgNeitherReadsNorChangesTheSettings() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val mine = sendJson(HttpMethod.Put, "$admin/settings", McpSettingsDto("teamcode"))
                assertEquals(HttpStatusCode.OK, mine.status, mine.bodyAsText())

                val readByB = read("$admin/settings", "admin-b").body<McpSettingsDto>()
                assertEquals(McpSettingsDto("ostrom", false), readByB, "B reads its own default, not A's prefix")
                val writtenByB = sendJson(HttpMethod.Put, "$admin/settings", McpSettingsDto("other"), "admin-b")
                assertEquals(McpSettingsDto("other", false), writtenByB.body<McpSettingsDto>())

                val readByA = read("$admin/settings").body<McpSettingsDto>()
                assertEquals(McpSettingsDto("teamcode", false), readByA, "A unchanged")
                assertEquals(McpSettingsDto("other", false), read("$admin/settings", "admin-b").body<McpSettingsDto>())
            }
        }
}
