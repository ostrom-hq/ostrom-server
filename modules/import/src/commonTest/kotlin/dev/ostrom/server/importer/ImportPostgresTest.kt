// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import dev.ostrom.server.importer.Importing.adminA
import dev.ostrom.server.importer.Importing.adminB
import dev.ostrom.server.importer.Importing.memberA
import dev.ostrom.server.importer.Importing.otherA
import dev.ostrom.server.importer.domain.AccountImporter
import dev.ostrom.server.importer.domain.ImportAction.CREATED
import dev.ostrom.server.importer.domain.ImportAction.PLANNED
import dev.ostrom.server.importer.domain.ImportAction.SKIPPED
import dev.ostrom.server.importer.domain.ImportAction.UPDATED
import dev.ostrom.server.importer.presentation.ImportReportDto
import dev.ostrom.server.importer.presentation.TeamCodeAccountDto
import dev.ostrom.server.importer.presentation.TeamCodeImportRequest
import dev.ostrom.server.importer.presentation.TeamCodeMcpServerDto
import dev.ostrom.server.importer.presentation.TeamCodeStdioDto
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.mcprelay.McpRelayModule
import dev.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpServersUseCase
import dev.ostrom.server.mcprelay.domain.McpSharing
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.FindSecretUseCase
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import dev.ostrom.server.secrets.domain.SecretValue
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: the import writes through the real secrets and MCP relay modules. */
class ImportPostgresTest {
    private val byToken = mapOf(
        "admin-a" to adminA,
        "member-a" to memberA,
        "admin-b" to adminB,
        "agent-a" to adminA,
        "device-a" to adminA,
        "cli-read" to adminA,
        "cli-import" to adminA,
    )

    private val principals = mapOf(
        "agent-a" to AuthenticatedPrincipal("agent-a", PrincipalKind.SERVICE, setOf(RelayScopes.AGENT_SESSION)),
        "device-a" to AuthenticatedPrincipal("device-a", PrincipalKind.DEVICE, setOf(RelayScopes.ALL_USER)),
        "cli-read" to AuthenticatedPrincipal("cli-read", PrincipalKind.USER, setOf(RelayScopes.MCP_READ)),
        "cli-import" to AuthenticatedPrincipal("cli-import", PrincipalKind.USER, setOf("import:write")),
        "stranger" to AuthenticatedPrincipal("stranger", PrincipalKind.USER, setOf(RelayScopes.ALL_USER)),
    )

    private fun overrides(accounts: AccountImporter? = null): Module = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = principals[token]
                    ?: byToken[token]?.let {
                        AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(RelayScopes.ALL_USER))
                    }
            }
        }
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    byToken[principal.subjectId]
            }
        }
        accounts?.let { importer -> single<AccountImporter> { importer } }
    }

    private val modules = listOf(SecretsModule, McpRelayModule, ImportModule)

    private suspend fun TestServer.import(
        request: TeamCodeImportRequest,
        token: String = "admin-a",
        dryRun: Boolean = false,
    ): HttpResponse = client.post("/api/v1/import/teamcode?dryRun=$dryRun") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    private suspend fun TestServer.report(
        request: TeamCodeImportRequest,
        token: String = "admin-a",
        dryRun: Boolean = false,
    ): ImportReportDto {
        val response = import(request, token, dryRun)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun TestServer.serverNames(caller: Caller) =
        koin.get<ListMcpServersUseCase>()(caller).map { it.name }.toSet()

    @Test
    fun aDryRunReportsThePlanAndWritesNothing() = serverTest(modules, overrides()) { server ->
        with(server) {
            val report = report(Importing.team(), dryRun = true)
            assertEquals(
                listOf(
                    line("setting", "agentPrefix", PLANNED),
                    line("secret", "TEAMCODE_GRAFANA", PLANNED),
                    line("server", "grafana", PLANNED),
                    line("policy", "grafana/update_dashboard", PLANNED),
                    line("server", "discord", PLANNED),
                    line("secret", "TEAMCODE_MYSQL_MYSQL_DSN", PLANNED),
                    line("server", "mysql", PLANNED),
                    line("server", "grafana.ro", PLANNED),
                ),
                report.items.take(8),
            )
            assertEquals(6, report.items.count { it.kind == "assignment" && it.action == "planned" })
            assertEquals(
                listOf(
                    line("account", "claude/Lyes", SKIPPED, NO_SHARING),
                    line("account", "codex/Lyes", SKIPPED, NO_SHARING),
                ),
                report.items.takeLast(2),
            )
            assertEquals(emptySet(), serverNames(adminA))
            assertEquals("ostrom", koin.get<GetMcpSettingsUseCase>()(adminA).agentPrefix)
        }
    }

    @Test
    fun anAdminImportsATeamCodeTeamAndAgainChangesNothing() = serverTest(modules, overrides()) { server ->
        with(server) {
            val first = report(Importing.team())
            assertTheFirstImport(first)
            assertTheImportedState()
            val again = report(Importing.team())
            assertEquals(0, again.created, again.items.toString())
            assertEquals(0, again.updated, again.items.toString())
            assertEquals(first.skipped, again.skipped)
            assertEquals(first.items.size, again.items.size)
        }
    }

    private fun assertTheFirstImport(report: ImportReportDto) {
        val expected = listOf(
            line("setting", "agentPrefix", UPDATED),
            line("secret", "TEAMCODE_GRAFANA", CREATED),
            line("server", "grafana", CREATED),
            line("policy", "grafana/update_dashboard", CREATED),
            line("server", "discord", CREATED),
            line("secret", "TEAMCODE_MYSQL_MYSQL_DSN", CREATED),
            line("server", "mysql", SKIPPED, HOST_DISABLED),
            line("server", "grafana.ro", CREATED),
            line("assignment", "grafana/${memberA.memberId}", CREATED),
            line("secret", "TEAMCODE_GRAFANA (personal, ${otherA.memberId})", SKIPPED, NOT_CARRIED),
            line("assignment", "grafana/${otherA.memberId}", CREATED),
            line("secret", "TEAMCODE_DISCORD (personal, ${memberA.memberId})", CREATED),
            line("assignment", "discord/${memberA.memberId}", CREATED),
            line("assignment", "discord/${otherA.memberId}", CREATED),
            line("assignment", "grafana.ro/${otherA.memberId}", CREATED),
            line("assignment", "grafana.ro/ghost", SKIPPED, "No Ostrom member is mapped to ghost."),
            line("account", "claude/Lyes", SKIPPED, NO_SHARING),
            line("account", "codex/Lyes", SKIPPED, NO_SHARING),
        )
        assertEquals(expected, report.items)
        assertEquals(listOf(12, 1, 0, 5), listOf(report.created, report.updated, report.unchanged, report.skipped))
    }

    private suspend fun TestServer.assertTheImportedState() {
        assertEquals("teamcode", koin.get<GetMcpSettingsUseCase>()(adminA).agentPrefix)
        val servers = koin.get<ListMcpServersUseCase>()(adminA).associateBy { it.name }
        assertEquals(setOf("grafana", "grafana.ro", "discord"), servers.keys)
        val grafana = servers.getValue("grafana")
        assertNull(grafana.credentialHeader, "TeamCode's Authorization is Ostrom's Bearer")
        val token = koin.get<RevealSecretsUseCase>()(adminA.orgId, listOfNotNull(grafana.credentialSecretId))
        assertEquals(listOf(SecretValue("g-token")), token.values.toList())
        val discord = servers.getValue("discord")
        assertEquals("X-Discord-Token", discord.credentialHeader)
        assertEquals(McpSharing.PER_MEMBER, discord.sharing)
        val own = koin.get<FindSecretUseCase>()(memberA, "TEAMCODE_DISCORD", personal = true)
        assertEquals(memberA.memberId, own.ownerMemberId)
        val discordAssignments = koin.get<ListMcpAssignmentsUseCase>()(adminA, discord.id).associateBy { it.memberId }
        assertEquals(own.id, discordAssignments.getValue(memberA.memberId).credentialSecretId)
        assertNull(discordAssignments.getValue(otherA.memberId).credentialSecretId)
        val readOnly = koin.get<ListMcpAssignmentsUseCase>()(adminA, servers.getValue("grafana.ro").id).single()
        assertEquals(setOf("query_loki_logs"), readOnly.policy.tools)
        val denied = koin.get<ListArgumentPoliciesUseCase>()(adminA, grafana.id).single()
        assertEquals("update_dashboard" to PolicyEffect.DENY, denied.tool to denied.effect)
        assertTrue(denied.conditions.isEmpty())
    }

    @Test
    fun aChangeInTeamCodeUpdatesOnlyWhatChanged() = serverTest(modules, overrides()) { server ->
        with(server) {
            report(Importing.team())
            val changed = Importing.team(
                grafanaToken = "g-token-2",
                grafanaUrl = "https://g.example.com/v2",
                readOnly = listOf("query_loki_logs", "list_datasources"),
            )
            val report = report(changed)
            assertEquals(
                listOf(
                    line("secret", "TEAMCODE_GRAFANA", UPDATED),
                    line("server", "grafana", UPDATED),
                    line("assignment", "grafana.ro/${otherA.memberId}", UPDATED),
                ),
                report.items.filter { it.action == "updated" },
            )
            val grafana = koin.get<ListMcpServersUseCase>()(adminA).single { it.name == "grafana" }
            assertEquals("https://g.example.com/v2", grafana.url)
            val token = koin.get<RevealSecretsUseCase>()(adminA.orgId, listOfNotNull(grafana.credentialSecretId))
            assertEquals(listOf(SecretValue("g-token-2")), token.values.toList())
        }
    }

    @Test
    fun anImportStaysInTheOrgOfItsCaller() = serverTest(modules, overrides()) { server ->
        with(server) {
            report(Importing.team())
            assertEquals(emptySet(), serverNames(adminB), "Org B sees none of org A's servers")
            assertFailsWith<SecretNotFoundException> {
                koin.get<FindSecretUseCase>()(adminB, "TEAMCODE_GRAFANA", false)
            }
            val other = TeamCodeImportRequest(
                mcpServers = listOf(
                    TeamCodeMcpServerDto(
                        "x",
                        "grafana",
                        "b",
                        url = "https://b.example.com/mcp",
                        sharedSecret = "b-token",
                    ),
                ),
            )
            val report = report(other, token = "admin-b")
            assertEquals(listOf("updated", "created", "created"), report.items.map { it.action })
            assertEquals(setOf("grafana"), serverNames(adminB))
            val grafanaA = koin.get<ListMcpServersUseCase>()(adminA).single { it.name == "grafana" }
            assertEquals("https://g.example.com/mcp", grafanaA.url, "Org B's import left org A's server alone")
            val tokenA = koin.get<RevealSecretsUseCase>()(adminA.orgId, listOfNotNull(grafanaA.credentialSecretId))
            assertEquals(listOf(SecretValue("g-token")), tokenA.values.toList())
        }
    }

    @Test
    fun sharedAccountsGoThroughTheSharingModuleWhenItIsInstalled() {
        val accounts = FakeAccountImporter()
        serverTest(modules, overrides(accounts)) { server ->
            with(server) {
                val plan = report(Importing.team(), dryRun = true)
                assertEquals(
                    listOf(line("account", "claude/Lyes", PLANNED), line("account", "codex/Lyes", PLANNED)),
                    plan.items.takeLast(2),
                )
                val report = report(Importing.team())
                assertEquals(
                    listOf(
                        line("account", "claude/Lyes", CREATED),
                        line("account", "codex/Lyes", SKIPPED, "This account is already shared."),
                    ),
                    report.items.takeLast(2),
                )
                assertEquals(listOf("${adminA.orgId}/claude/access-claude"), accounts.imported)
            }
        }
    }

    @Test
    fun onlyAnAdminWithAConsoleCredentialMayImport() = serverTest(modules, overrides()) { server ->
        with(server) {
            val empty = TeamCodeImportRequest()
            assertEquals(HttpStatusCode.Unauthorized, import(empty, token = "nobody").status)
            assertEquals(HttpStatusCode.Forbidden, import(empty, token = "member-a").status, "Members may not")
            assertEquals(HttpStatusCode.Forbidden, import(empty, token = "agent-a").status, "Agents may not")
            assertEquals(HttpStatusCode.Forbidden, import(empty, token = "device-a").status, "Devices may not")
            assertEquals(HttpStatusCode.Forbidden, import(empty, token = "cli-read").status, "Read tokens may not")
            assertEquals(HttpStatusCode.NotFound, import(empty, token = "stranger").status, "Not a member of an org")
            assertEquals(HttpStatusCode.OK, import(empty, token = "cli-import", dryRun = true).status)
            assertEquals(emptySet(), serverNames(adminA))
        }
    }

    @Test
    fun aMalformedRequestIsRefusedBeforeAnyWrite() = serverTest(modules, overrides()) { server ->
        with(server) {
            val stdio = TeamCodeStdioDto("cargo", "x@1.0.0")
            val malformed = listOf(
                TeamCodeImportRequest(memberMap = mapOf("m1" to "not-a-uuid")),
                TeamCodeImportRequest(mcpServers = listOf(TeamCodeMcpServerDto("s", "x", "v", stdio = stdio))),
                TeamCodeImportRequest(mcpServers = listOf(TeamCodeMcpServerDto("s", "x", "v", toolMode = "maybe"))),
                TeamCodeImportRequest(
                    accounts = listOf(TeamCodeAccountDto("claude", "c", "Lyes", null, "a", "r", "tomorrow")),
                ),
            )
            for (request in malformed) {
                assertEquals(HttpStatusCode.BadRequest, import(request).status, request.toString())
            }
            assertEquals(emptySet(), serverNames(adminA))
            assertEquals("ostrom", koin.get<GetMcpSettingsUseCase>()(adminA).agentPrefix)
        }
    }

    private companion object {
        const val NO_SHARING = "This server does not import shared accounts: share them again from the console."
        const val NOT_CARRIED = "Only an HTTP per-member server carries a member's credential: set it again in Ostrom."
        const val HOST_DISABLED =
            "No MCP host is configured on this server (OSTROM_MCP_HOST_URL), so it cannot run a stdio server."
    }
}
