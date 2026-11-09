// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer

import run.ostrom.server.importer.Importing.adminA
import run.ostrom.server.importer.Importing.memberA
import run.ostrom.server.importer.Importing.modules
import run.ostrom.server.importer.Importing.otherA
import run.ostrom.server.importer.domain.ImportAction.CREATED
import run.ostrom.server.importer.domain.ImportAction.PLANNED
import run.ostrom.server.importer.domain.ImportAction.SKIPPED
import run.ostrom.server.importer.domain.ImportAction.UNCHANGED
import run.ostrom.server.importer.domain.ImportAction.UPDATED
import run.ostrom.server.importer.presentation.TeamCodeAccountDto
import run.ostrom.server.importer.presentation.TeamCodeAssignmentDto
import run.ostrom.server.importer.presentation.TeamCodeImportRequest
import run.ostrom.server.importer.presentation.TeamCodeMcpServerDto
import run.ostrom.server.importer.presentation.TeamCodeStdioDto
import run.ostrom.server.mcprelay.domain.AssignMcpServerUseCase
import run.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import run.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import run.ostrom.server.mcprelay.domain.McpServerPayload
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.ToolPolicy
import run.ostrom.server.mcprelay.domain.UpdateMcpServerUseCase
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A second run makes Ostrom match TeamCode again, on what the import created and nothing else. */
class ImportResyncPostgresTest {
    private fun logs(members: List<String>, id: String = "v-logs", tools: List<String> = emptyList()) =
        TeamCodeImportRequest(
            mcpServers = listOf(
                TeamCodeMcpServerDto(
                    id,
                    "logs",
                    "default",
                    url = "https://l.example.com/mcp",
                    toolMode = "deny",
                    tools = tools,
                ),
            ),
            assignments = members.map { TeamCodeAssignmentDto(it, id) },
            memberMap = Importing.members,
        )

    private suspend fun TestServer.assignees() =
        koin.get<ListMcpAssignmentsUseCase>()(adminA, servers().getValue("logs").id).map { it.memberId }.toSet()

    @Test
    fun aMemberRevokedInTeamCodeLosesTheServer() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            report(logs(listOf("m1", "m2")))
            val logs = servers().getValue("logs").id
            koin.get<AssignMcpServerUseCase>()(adminA, logs, null, ToolPolicy(null), null)
            val revoked = listOf("logs/${otherA.memberId}", "logs/everyone")

            val dry = report(logs(listOf("m1")), dryRun = true)
            val dryLines = revoked.map { line("assignment", it, PLANNED, REVOKED) }.toSet()
            assertEquals(dryLines, dry.items.takeLast(2).toSet())
            assertEquals(setOf(memberA.memberId, otherA.memberId, null), assignees(), "A dry run removes nothing")

            val real = report(logs(listOf("m1")))
            val realLines = revoked.map { line("assignment", it, UPDATED, REVOKED) }.toSet()
            assertEquals(realLines, real.items.takeLast(2).toSet())
            assertEquals(setOf(memberA.memberId), assignees())

            val nobody = report(logs(emptyList()))
            assertEquals(line("assignment", "logs/${memberA.memberId}", UPDATED, REVOKED), nobody.items.last())
            assertEquals(emptySet(), assignees())
        }
    }

    @Test
    fun aServerRenamedInTheConsoleIsStillTheImportsOwn() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            report(logs(listOf("m1")))
            val imported = servers().getValue("logs")
            koin.get<UpdateMcpServerUseCase>()(
                adminA,
                imported.id,
                McpServerPayload("logs-prod", McpTransport.HTTP, url = imported.url),
            )
            val again = report(logs(listOf("m1")))
            assertEquals(line("server", "logs", UPDATED), again.items.single { it.kind == "server" })
            assertEquals(mapOf("logs" to imported.id), servers().mapValues { it.value.id }, "Never a second server")
        }
    }

    @Test
    fun aServerImportedFromAnotherVariantIsLeftAlone() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            report(logs(listOf("m1"), id = "v-old"))
            val replaced = report(logs(listOf("m1"), id = "v-new"))
            val reason = "A server named logs was imported from another TeamCode variant: delete it to import this one."
            assertEquals(line("server", "logs", SKIPPED, reason), replaced.items.single { it.kind == "server" })
            assertEquals(setOf(memberA.memberId), assignees(), "The old variant's server is not taken over")
        }
    }

    @Test
    fun aToolDeniedTwiceInTeamCodeIsDeniedOnce() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val first = report(logs(listOf("m1"), tools = listOf("drop", "drop")))
            assertEquals(listOf(line("policy", "logs/drop", CREATED)), first.items.filter { it.kind == "policy" })
            val policies = koin.get<ListArgumentPoliciesUseCase>()(adminA, servers().getValue("logs").id)
            assertEquals(listOf("drop"), policies.map { it.tool })
            val again = report(logs(listOf("m1"), tools = listOf("drop", "drop")))
            assertEquals(listOf(line("policy", "logs/drop", UNCHANGED)), again.items.filter { it.kind == "policy" })
        }
    }

    @Test
    fun aRequestNeverPrintsItsCredentials() {
        val request = TeamCodeImportRequest(
            mcpServers = listOf(
                TeamCodeMcpServerDto(
                    "v-1",
                    "grafana",
                    "default",
                    sharedSecret = "shared-secret-value",
                    stdio = TeamCodeStdioDto("npm", "mcp-grafana@1.0.0", secretEnv = mapOf("TOKEN" to "env-value")),
                ),
            ),
            assignments = listOf(TeamCodeAssignmentDto("m1", "v-1", memberSecret = "member-secret-value")),
            accounts = listOf(
                TeamCodeAccountDto(
                    provider = "claude",
                    providerAccountId = "claude-1",
                    label = "Lena",
                    accessToken = "access-token-value",
                    refreshToken = "refresh-token-value",
                    expiresAt = "2026-11-01T00:00:00Z",
                ),
            ),
        )
        val printed = request.toString()
        for (secret in listOf("shared-secret", "env", "member-secret", "access-token", "refresh-token")) {
            assertFalse("$secret-value" in printed, printed)
        }
        assertTrue("v-1" in printed && "TOKEN" in printed && "claude-1" in printed, printed)
    }

    private companion object {
        const val REVOKED = "Removed: TeamCode no longer assigns this server to this member."
    }
}
