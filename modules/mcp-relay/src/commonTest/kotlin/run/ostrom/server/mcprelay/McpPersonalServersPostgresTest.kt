// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.McpServerLimitException
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.PersonalServerOutcome
import run.ostrom.server.mcprelay.presentation.ArgumentPolicyRequest
import run.ostrom.server.mcprelay.presentation.McpAssignmentRequest
import run.ostrom.server.mcprelay.presentation.McpAssignmentsPage
import run.ostrom.server.mcprelay.presentation.McpServerDto
import run.ostrom.server.mcprelay.presentation.McpServersPage
import run.ostrom.server.mcprelay.presentation.McpSettingsDto
import run.ostrom.server.mcprelay.presentation.StdioSpecDto
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.presentation.SecretWriteRequest
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val MINE = "/api/v1/me/mcp/servers"

/** A member's own MCP servers over HTTP and on PostgreSQL (TeamCode #269, docs/variants.md "Personal variants"). */
class McpPersonalServersPostgresTest : RelayPostgresHarness() {
    private fun remote(name: String, secrets: List<String> = emptyList()) =
        McpServerDto(name, "https://mcp.example.com/$name", "http", secretNames = secrets)

    private suspend fun TestServer.page(path: String, token: String) =
        client.get(path) { bearerAuth(token) }.body<McpServersPage>().items

    private suspend fun HttpResponse.expect(status: HttpStatusCode) = assertEquals(status, this.status, bodyAsText())

    private suspend fun HttpResponse.refusedAs(status: HttpStatusCode, type: String) {
        val text = bodyAsText()
        assertEquals(status, this.status, text)
        assertTrue("\"$type\"" in text, text)
    }

    @Test
    fun aMembersOwnServerIsRelayedToThemAloneInTheOrganizationsNameSpace() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val created = sendJson(HttpMethod.Post, MINE, remote("mine"), "member-a")
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                assertEquals(Relay.memberA.memberId.toString(), created.body<McpServerDto>().owner)
                assertEquals(listOf("mine"), page(MINE, "member-a").map { it.name })
                assertEquals(emptyList(), page(MINE, "other-a"))
                assertEquals(listOf("mine"), page("$admin/servers?mine=true", "member-a").map { it.name })
                assertEquals(emptyList(), page("$admin/servers?mine=true", "other-a"), "Assigned to its owner alone")

                // One name space: another member's, the team's.
                sendJson(HttpMethod.Post, MINE, remote("mine"), "other-a").expect(HttpStatusCode.Conflict)
                sendJson(HttpMethod.Post, "$admin/servers", remote("mine")).expect(HttpStatusCode.Conflict)
                grafana()
                sendJson(HttpMethod.Post, MINE, remote("grafana"), "member-a").expect(HttpStatusCode.Conflict)

                // Another member's server, or a team one, is not found.
                sendJson(HttpMethod.Put, "$MINE/mine", remote("mine"), "other-a").expect(HttpStatusCode.NotFound)
                assertEquals(HttpStatusCode.NotFound, client.delete("$MINE/mine") { bearerAuth("other-a") }.status)
                sendJson(HttpMethod.Put, "$MINE/grafana", remote("grafana"), "member-a").expect(HttpStatusCode.NotFound)
                sendJson(HttpMethod.Put, "$MINE/mine", remote("other"), "member-a").expect(HttpStatusCode.BadRequest)
                val changed = sendJson(HttpMethod.Put, "$MINE/mine", remote("mine"), "member-a")
                assertEquals(2, changed.body<McpServerDto>().version)

                assertEquals(listOf("grafana"), page("$admin/servers", "admin-a").map { it.name }, "The team's")
                val review = page("$admin/servers?personal=true", "admin-a")
                assertEquals(listOf("mine" to Relay.memberA.memberId.toString()), review.map { it.name to it.owner })
                assertEquals(listOf("mine"), page("$admin/servers?personal=true", "member-a").map { it.name })
            }
        }

    @Test
    fun anAdministratorReviewsPromotesOrDeletesButNeverChangesAMembersServer() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                sendJson(HttpMethod.Post, MINE, remote("mine"), "member-a").expect(HttpStatusCode.Created)
                val path = "$admin/servers/mine"
                sendJson(HttpMethod.Put, path, remote("mine")).refusedAs(HttpStatusCode.Conflict, "mcp_server_personal")
                val assignment = "$path/assignments/${Relay.otherA.memberId}"
                sendJson(HttpMethod.Put, assignment, McpAssignmentRequest())
                    .refusedAs(HttpStatusCode.Conflict, "mcp_server_personal")
                client.delete("$path/assignments/${Relay.memberA.memberId}") { bearerAuth("admin-a") }
                    .refusedAs(HttpStatusCode.Conflict, "mcp_server_personal")
                sendJson(HttpMethod.Post, "$path/policies", ArgumentPolicyRequest("*", emptyList(), "deny", "No."))
                    .refusedAs(HttpStatusCode.Conflict, "mcp_server_personal")
                val assigned = client.get("$path/assignments") { bearerAuth("admin-a") }.body<McpAssignmentsPage>()
                assertEquals(listOf(Relay.memberA.memberId.toString()), assigned.items.map { it.member })

                // Promotion: the owner keeps their assignment; a team server comes back as it is.
                grafana()
                val team = client.post("$admin/servers/grafana/promote") { bearerAuth("admin-a") }
                assertEquals(HttpStatusCode.OK, team.status)
                client.post("$path/promote") { bearerAuth("member-a") }.expect(HttpStatusCode.Forbidden)
                client.post("$path/promote") { bearerAuth("stale-admin-a") }.expect(HttpStatusCode.Forbidden)
                val promoted = client.post("$path/promote") { bearerAuth("admin-a") }.body<McpServerDto>()
                assertNull(promoted.owner)
                assertEquals(emptyList(), page(MINE, "member-a"))
                assertEquals(listOf("grafana", "mine"), page("$admin/servers", "admin-a").map { it.name })
                assertEquals(listOf("mine"), page("$admin/servers?mine=true", "member-a").map { it.name })
                client.post("$admin/servers/nope/promote") { bearerAuth("admin-a") }.expect(HttpStatusCode.NotFound)

                // A member deletes their own; an administrator deletes anyone's.
                sendJson(HttpMethod.Post, MINE, remote("temp"), "member-a").expect(HttpStatusCode.Created)
                assertEquals(HttpStatusCode.NoContent, client.delete("$MINE/temp") { bearerAuth("member-a") }.status)
                assertEquals(HttpStatusCode.NotFound, client.delete("$MINE/temp") { bearerAuth("member-a") }.status)
                sendJson(HttpMethod.Post, MINE, remote("temp"), "other-a").expect(HttpStatusCode.Created)
                val deleted = client.delete("$admin/servers/temp") { bearerAuth("admin-a") }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals(emptyList(), page(MINE, "other-a"))
            }
        }

    @Test
    fun aHostedCommandWaitsForTheTeamsSettingAndCredentialsAreTheMembersOwn() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val hosted = McpServerDto("db", transport = "stdio", stdio = StdioSpecDto("npm", "db-mcp@1.0.0"))
                sendJson(HttpMethod.Post, MINE, hosted, "member-a")
                    .refusedAs(HttpStatusCode.Forbidden, "hosted_not_allowed")
                val on = sendJson(HttpMethod.Put, "$admin/settings", McpSettingsDto("ostrom", true))
                assertEquals(McpSettingsDto("ostrom", true), on.body<McpSettingsDto>())
                val kept = sendJson(HttpMethod.Put, "$admin/settings", McpSettingsDto("teamcode"))
                assertEquals(McpSettingsDto("teamcode", true), kept.body<McpSettingsDto>(), "Absent keeps it")
                // Allowed now: this server has no MCP host, which is the next refusal.
                val noHost = sendJson(HttpMethod.Post, MINE, hosted, "member-a")
                assertEquals(HttpStatusCode.Conflict, noHost.status, noHost.bodyAsText())
                assertTrue("MCP host" in noHost.bodyAsText(), noHost.bodyAsText())

                // The org's secret is not the member's: their own personal secret is.
                sendJson(HttpMethod.Post, "/api/v1/secrets", SecretWriteRequest("TEAM_TOKEN", "team_token_value"))
                    .expect(HttpStatusCode.Created)
                sendJson(HttpMethod.Post, MINE, remote("sentry", listOf("TEAM_TOKEN")), "member-a")
                    .expect(HttpStatusCode.BadRequest)
                sendJson(HttpMethod.Post, "/api/v1/secrets", SecretWriteRequest("MINE", "member_own_token", "personal"), "member-a")
                    .expect(HttpStatusCode.Created)
                val withSecret = sendJson(HttpMethod.Post, MINE, remote("sentry", listOf("MINE")), "member-a")
                assertEquals(listOf("MINE"), withSecret.body<McpServerDto>().secretNames)
                val renewed = sendJson(HttpMethod.Put, "$MINE/sentry", remote("sentry", listOf("MINE")), "member-a")
                assertEquals(listOf("MINE"), renewed.body<McpServerDto>().secretNames)
                assertEquals(listOf("MINE"), page(MINE, "member-a").single().secretNames)
                // A team server would send the owner's secret for everyone.
                val keyed = client.post("$admin/servers/sentry/promote") { bearerAuth("admin-a") }
                assertEquals(HttpStatusCode.Conflict, keyed.status)
            }
        }

    @Test
    fun onlyThePersonSignedInRecentlyChangesTheirOwnServersAndAMemberKeepsTwenty() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                assertEquals(HttpStatusCode.Unauthorized, client.get(MINE).status)
                assertEquals(HttpStatusCode.Forbidden, client.get(MINE) { bearerAuth("agent-a") }.status)
                assertEquals(HttpStatusCode.OK, client.get(MINE) { bearerAuth("cli-a") }.status)
                sendJson(HttpMethod.Post, MINE, remote("mine"), "cli-a").expect(HttpStatusCode.Forbidden)
                sendJson(HttpMethod.Post, MINE, remote("mine"), "stale-admin-a").expect(HttpStatusCode.Forbidden)
                assertEquals(HttpStatusCode.Unauthorized, client.delete("$MINE/mine").status)

                repeat(20) {
                    sendJson(HttpMethod.Post, MINE, remote("m$it"), "member-a").expect(HttpStatusCode.Created)
                }
                sendJson(HttpMethod.Post, MINE, remote("m20"), "member-a")
                    .refusedAs(HttpStatusCode.Conflict, "mcp_server_limit")
                assertEquals(20, page(MINE, "member-a").size)
            }
        }

    @Test
    fun theRepositoryKeepsOwnersTheTeamsLimitAndAnotherOrganizationApart() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            val repository = server.koin.get<McpRelayRepository>()
            val mine = Relay.server("mine").copy(ownerMemberId = Relay.memberA.memberId)
            assertEquals(PersonalServerOutcome.CREATED, repository.createPersonalServer(mine, 20))
            val again = repository.createPersonalServer(mine.copy(id = Uuid.random()), 20)
            assertEquals(PersonalServerOutcome.NAME_TAKEN, again)
            assertEquals(PersonalServerOutcome.LIMIT, repository.createPersonalServer(mine.copy(name = "two"), 1))
            assertEquals(mine, repository.getServerByName(Relay.orgA, "mine"))
            assertNull(repository.getServerByName(Relay.orgB, "mine"), "Another org misses it")
            assertEquals(emptyList(), repository.listPersonalServers(Relay.orgB, null, 10))
            assertNull(repository.updateServer(mine.copy(ownerMemberId = null)), "A team update misses a member's")
            assertNull(repository.updateServer(mine.copy(ownerMemberId = Relay.otherA.memberId)))
            assertEquals(false, repository.deleteServer(Relay.orgA, mine.id, Relay.otherA.memberId))

            val team = Relay.server("grafana", transport = McpTransport.HTTP)
            assertTrue(repository.createServer(team))
            assertNull(repository.promoteServer(Relay.orgA, team.id, 1, 64), "Only a member's server is promoted")
            assertFailsWith<McpServerLimitException> { repository.promoteServer(Relay.orgA, mine.id, 1, 1) }
            assertNull(repository.promoteServer(Relay.orgB, mine.id, 1, 64))
            assertNull(repository.promoteServer(Relay.orgA, mine.id, 2, 64), "Changed since it was read")
            val promoted = repository.promoteServer(Relay.orgA, mine.id, 1, 64)
            assertEquals(null to 2, promoted?.ownerMemberId to promoted?.version)
        }
}
