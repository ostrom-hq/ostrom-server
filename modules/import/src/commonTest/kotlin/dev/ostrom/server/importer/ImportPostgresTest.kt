// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import dev.ostrom.server.importer.Importing.adminA
import dev.ostrom.server.importer.Importing.adminB
import dev.ostrom.server.importer.Importing.memberA
import dev.ostrom.server.importer.Importing.modules
import dev.ostrom.server.importer.Importing.otherA
import dev.ostrom.server.importer.domain.ImportAction.CREATED
import dev.ostrom.server.importer.domain.ImportAction.PLANNED
import dev.ostrom.server.importer.domain.ImportAction.SKIPPED
import dev.ostrom.server.importer.domain.ImportAction.UNCHANGED
import dev.ostrom.server.importer.domain.ImportAction.UPDATED
import dev.ostrom.server.importer.presentation.ImportReportDto
import dev.ostrom.server.importer.presentation.TeamCodeAccountDto
import dev.ostrom.server.importer.presentation.TeamCodeImportRequest
import dev.ostrom.server.importer.presentation.TeamCodeMcpServerDto
import dev.ostrom.server.importer.presentation.TeamCodeStdioDto
import dev.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import dev.ostrom.server.mcprelay.domain.McpSharing
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import dev.ostrom.server.secrets.domain.FindSecretUseCase
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import dev.ostrom.server.secrets.domain.SecretValue
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: the import writes through the real secrets and MCP relay modules. */
class ImportPostgresTest {
    @Test
    fun aDryRunReportsThePlanAndWritesNothing() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val report = report(Importing.team(), dryRun = true)
            assertEquals(
                listOf(
                    line("setting", "agentPrefix", PLANNED),
                    line("server", "discord", PLANNED),
                    line("secret", "TEAMCODE_GRAFANA", PLANNED),
                    line("server", "grafana", PLANNED),
                    line("policy", "grafana/update_dashboard", PLANNED),
                    line("secret", "TEAMCODE_MYSQL_MYSQL_DSN", PLANNED),
                    line("server", "mysql", PLANNED),
                    line("server", "grafana.ro", PLANNED),
                    line("assignment", "grafana/${memberA.memberId}", PLANNED),
                    line("secret", "TEAMCODE_GRAFANA (personal, ${otherA.memberId})", SKIPPED, NOT_CARRIED),
                    line("assignment", "grafana/${otherA.memberId}", PLANNED),
                    line("secret", "TEAMCODE_DISCORD (personal, ${memberA.memberId})", PLANNED),
                    line("assignment", "discord/${memberA.memberId}", PLANNED),
                    line("assignment", "discord/${otherA.memberId}", PLANNED),
                    line("assignment", "grafana.ro/${otherA.memberId}", PLANNED),
                    line("assignment", "mysql/${memberA.memberId}", PLANNED),
                    line("assignment", "grafana.ro/ghost", SKIPPED, "No Ostrom member is mapped to ghost."),
                    line("account", "claude/Lena", SKIPPED, NO_SHARING),
                    line("account", "codex/Lena", SKIPPED, NO_SHARING),
                ),
                report.items,
            )
            assertEquals(emptySet(), servers().keys)
            assertEquals("ostrom", koin.get<GetMcpSettingsUseCase>()(adminA).agentPrefix)
            val real = report(Importing.team())
            assertEquals(report.items.size, real.items.size, "The real run reports as many lines as its dry run")
        }
    }

    @Test
    fun anAdminImportsATeamCodeTeamAndAgainChangesNothing() = serverTest(modules, importOverrides()) { server ->
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
            line("server", "discord", CREATED),
            line("secret", "TEAMCODE_GRAFANA", CREATED),
            line("server", "grafana", CREATED),
            line("policy", "grafana/update_dashboard", CREATED),
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
            line("assignment", "mysql/${memberA.memberId}", SKIPPED, NOT_IMPORTED),
            line("assignment", "grafana.ro/ghost", SKIPPED, "No Ostrom member is mapped to ghost."),
            line("account", "claude/Lena", SKIPPED, NO_SHARING),
            line("account", "codex/Lena", SKIPPED, NO_SHARING),
        )
        assertEquals(expected, report.items)
        assertEquals(listOf(12, 1, 0, 6), listOf(report.created, report.updated, report.unchanged, report.skipped))
    }

    private suspend fun TestServer.assertTheImportedState() {
        assertEquals("teamcode", koin.get<GetMcpSettingsUseCase>()(adminA).agentPrefix)
        val servers = servers()
        assertEquals(setOf("grafana", "grafana.ro", "discord"), servers.keys)
        val grafana = servers.getValue("grafana")
        assertNull(grafana.credentialHeader, "TeamCode's Authorization is Ostrom's Bearer")
        val token = koin.get<RevealSecretsUseCase>()(adminA.orgId, listOfNotNull(grafana.credentialSecretId))
        assertEquals(listOf(SecretValue("g-token-1234")), token.values.toList())
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

    private fun changed() = Importing.team(
        grafanaToken = "g-token-2",
        grafanaUrl = "https://g.example.com/v2",
        readOnly = listOf("query_loki_logs", "list_datasources"),
        denied = listOf("update_dashboard", "delete_dashboard"),
    )

    @Test
    fun aChangeInTeamCodeUpdatesOnlyWhatChanged() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            report(Importing.team())
            val report = report(changed())
            assertEquals(
                listOf(
                    line("secret", "TEAMCODE_GRAFANA", UPDATED),
                    line("server", "grafana", UPDATED),
                    line("assignment", "grafana.ro/${otherA.memberId}", UPDATED),
                ),
                report.items.filter { it.action == "updated" },
            )
            assertEquals(
                listOf(line("policy", "grafana/delete_dashboard", CREATED)),
                report.items.filter { it.action == "created" },
            )
            val grafana = servers().getValue("grafana")
            assertEquals("https://g.example.com/v2", grafana.url)
            val token = koin.get<RevealSecretsUseCase>()(adminA.orgId, listOfNotNull(grafana.credentialSecretId))
            assertEquals(listOf(SecretValue("g-token-2")), token.values.toList())
            assertEquals(2, koin.get<ListArgumentPoliciesUseCase>()(adminA, grafana.id).size)
        }
    }

    @Test
    fun aDryRunAfterAnImportReportsWhatIsUnchangedAndWhatWouldChange() =
        serverTest(modules, importOverrides()) { server ->
            with(server) {
                report(Importing.team())
                val same = report(Importing.team(), dryRun = true)
                // mysql was refused by the relay (no MCP host): only the relay's own check could say so again.
                assertEquals(
                    listOf(line("server", "mysql", PLANNED), line("assignment", "mysql/${memberA.memberId}", PLANNED)),
                    same.items.filter { it.action == "planned" },
                )
                assertEquals(13, same.unchanged)
                val plan = report(changed(), dryRun = true)
                val lines = plan.items.associateBy { it.kind to it.name }
                assertEquals(
                    listOf(
                        line("secret", "TEAMCODE_GRAFANA", PLANNED),
                        line("server", "grafana", PLANNED),
                        line("policy", "grafana/update_dashboard", UNCHANGED),
                        line("policy", "grafana/delete_dashboard", PLANNED),
                        line("assignment", "grafana.ro/${otherA.memberId}", PLANNED),
                    ),
                    listOf(
                        "secret" to "TEAMCODE_GRAFANA",
                        "server" to "grafana",
                        "policy" to "grafana/update_dashboard",
                        "policy" to "grafana/delete_dashboard",
                        "assignment" to "grafana.ro/${otherA.memberId}",
                    ).map(lines::getValue),
                )
                assertEquals(0, plan.created + plan.updated, "A dry run writes nothing")
                val grafana = servers().getValue("grafana")
                assertEquals("https://g.example.com/mcp", grafana.url)
                val token = koin.get<RevealSecretsUseCase>()(adminA.orgId, listOfNotNull(grafana.credentialSecretId))
                assertEquals(listOf(SecretValue("g-token-1234")), token.values.toList())
                assertEquals(1, koin.get<ListArgumentPoliciesUseCase>()(adminA, grafana.id).size)
            }
        }

    @Test
    fun anImportStaysInTheOrgOfItsCaller() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            report(Importing.team())
            assertEquals(emptySet(), servers(adminB).keys, "Org B sees none of org A's servers")
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
                        sharedSecret = "b-token-value",
                    ),
                ),
            )
            val report = report(other, token = "admin-b")
            assertEquals(listOf("updated", "created", "created"), report.items.map { it.action })
            assertEquals(setOf("grafana"), servers(adminB).keys)
            val grafanaA = servers().getValue("grafana")
            assertEquals("https://g.example.com/mcp", grafanaA.url, "Org B's import left org A's server alone")
            val tokenA = koin.get<RevealSecretsUseCase>()(adminA.orgId, listOfNotNull(grafanaA.credentialSecretId))
            assertEquals(listOf(SecretValue("g-token-1234")), tokenA.values.toList())
        }
    }

    @Test
    fun rowLevelSecurityConfinesTheImportsRecordsToTheirOrg() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            report(Importing.team())
            val unfiltered = Statement.create("SELECT source_key FROM import_provenance")
            assertEquals(6, driver.fetchInOrg(adminA.orgId, unfiltered).getOrThrow().size)
            assertEquals(0, driver.fetchInOrg(adminB.orgId, unfiltered).getOrThrow().size, "No WHERE, no leak")
            val stolen = Statement.create("UPDATE import_provenance SET ostrom_name = 'taken'")
            assertEquals(0L, driver.executeInOrg(adminB.orgId, stolen).getOrThrow())
            val planted = Statement.create(
                "INSERT INTO import_provenance (org_id, kind, ostrom_id, source_key, ostrom_name) " +
                    "VALUES (:org, 'SERVER', :id, 'planted', 'planted')",
            ).bind("org", adminA.orgId).bind("id", Uuid.random())
            assertFails("Another org's row is refused") { driver.executeInOrg(adminB.orgId, planted).getOrThrow() }
            assertEquals(6, driver.fetchInOrg(adminA.orgId, unfiltered).getOrThrow().size, "Nothing was planted")
            // Org B's same-named server is not org A's: its import creates its own, recorded in its own org.
            report(Importing.team().copy(memberMap = mapOf("m1" to adminB.memberId.toString())), token = "admin-b")
            assertEquals(6, driver.fetchInOrg(adminB.orgId, unfiltered).getOrThrow().size)
        }
    }

    @Test
    fun aMemberMapNamingAnyoneButTheOrgsMembersIsRefusedBeforeAnyWrite() =
        serverTest(modules, importOverrides()) { server ->
            with(server) {
                // Org B's admin maps TeamCode's members to org A's: refused, nothing written, even in a dry run.
                for (dryRun in listOf(true, false)) {
                    val response = import(Importing.team(), token = "admin-b", dryRun = dryRun)
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertTrue(strangers("m1, m2") in response.bodyAsText(), response.bodyAsText())
                }
                assertEquals(emptySet(), servers(adminB).keys)
                // In its own org, an id that is no member is refused too, and only that one is named.
                val unknown = Importing.members + ("m3" to Uuid.random().toString())
                val response = import(Importing.team().copy(memberMap = unknown))
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(strangers("m3") in response.bodyAsText(), response.bodyAsText())
                assertEquals(emptySet(), servers().keys)
                report(Importing.team())
                val imported = setOf("discord", "grafana", "grafana.ro")
                assertEquals(imported, servers().keys, "Mapped to members only: imported")
            }
        }

    @Test
    fun withoutAMemberDirectoryNoMemberCanBeMapped() =
        serverTest(modules, importOverrides(members = null)) { server ->
            with(server) {
                val response = import(Importing.team())
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(strangers("m1, m2") in response.bodyAsText(), response.bodyAsText())
                assertEquals(emptySet(), servers().keys)
                report(Importing.team().copy(memberMap = emptyMap()))
                assertEquals(setOf("discord", "grafana", "grafana.ro"), servers().keys)
            }
        }

    @Test
    fun withoutIdentityBoundTheRouteStillLoadsAndRefusesStrangers() =
        serverTest(modules, module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } }) { server ->
            assertEquals(HttpStatusCode.Unauthorized, server.client.post("/api/v1/import/teamcode").status)
        }

    @Test
    fun aCutoverIsRefusedWhereNoSharedAccountIsImported() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val response = import(Importing.team(), "owner-a", cutover = true)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("cutover_sharing_unavailable", response.errorType())
            assertEquals(emptyMap(), servers(), "Nothing imported")
        }
    }

    @Test
    fun sharedAccountsGoThroughTheSharingModuleWhenItIsInstalled() {
        val accounts = FakeAccountImporter()
        serverTest(modules, importOverrides(accounts)) { server ->
            with(server) {
                val plan = report(Importing.team(), dryRun = true)
                assertEquals(
                    listOf(
                        line("account", "claude/Lena", PLANNED),
                        line("account", "codex/Lena", SKIPPED, "This account is already shared."),
                    ),
                    plan.items.takeLast(2),
                    "A dry run makes the importer's checks",
                )
                assertEquals(emptyList(), accounts.imported, "A dry run imports no account")
                val report = report(Importing.team())
                assertEquals(
                    listOf(
                        line("account", "claude/Lena", CREATED),
                        line("account", "codex/Lena", SKIPPED, "This account is already shared."),
                    ),
                    report.items.takeLast(2),
                )
                assertEquals(listOf("${adminA.orgId}/claude/access-claude"), accounts.imported)
            }
        }
    }

    @Test
    fun onlyAnAdminWithAConsoleCredentialMayImport() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val empty = TeamCodeImportRequest()
            assertEquals(HttpStatusCode.Unauthorized, import(empty, token = "nobody").status)
            assertEquals(HttpStatusCode.Forbidden, import(empty, token = "member-a").status, "Members may not")
            assertEquals(HttpStatusCode.Forbidden, import(empty, token = "agent-a").status, "Agents may not")
            assertEquals(HttpStatusCode.Forbidden, import(empty, token = "device-a").status, "Devices may not")
            assertEquals(HttpStatusCode.Forbidden, import(empty, token = "cli-read").status, "Read tokens may not")
            assertEquals(HttpStatusCode.NotFound, import(empty, token = "stranger").status, "Not a member of an org")
            assertEquals(HttpStatusCode.OK, import(empty, token = "cli-import", dryRun = true).status)
            assertEquals(emptySet(), servers().keys)
        }
    }

    @Test
    fun aMalformedRequestIsRefusedBeforeAnyWrite() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val stdio = TeamCodeStdioDto("cargo", "x@1.0.0")
            val malformed = listOf(
                TeamCodeImportRequest(memberMap = mapOf("m1" to "not-a-uuid")),
                TeamCodeImportRequest(mcpServers = listOf(TeamCodeMcpServerDto("s", "x", "v", stdio = stdio))),
                TeamCodeImportRequest(mcpServers = listOf(TeamCodeMcpServerDto("s", "x", "v", toolMode = "maybe"))),
                TeamCodeImportRequest(
                    accounts = listOf(TeamCodeAccountDto("claude", "c", "Lena", null, "a", "r", "tomorrow")),
                ),
                // An instant the parser reads but PostgreSQL cannot store: a 400, never the database's 500.
                TeamCodeImportRequest(
                    accounts = listOf(TeamCodeAccountDto("claude", "c", "Lena", null, "a", "r", BEYOND)),
                ),
            )
            for (request in malformed) {
                assertEquals(HttpStatusCode.BadRequest, import(request).status, request.toString())
            }
            assertEquals(emptySet(), servers().keys)
            assertEquals("ostrom", koin.get<GetMcpSettingsUseCase>()(adminA).agentPrefix)
        }
    }

    companion object {
        const val NO_SHARING = "This server does not import shared accounts: share them again from the console."
        const val NOT_CARRIED = "Only an HTTP per-member server carries a member's credential: set it again in Ostrom."
        const val NOT_IMPORTED = "Its server was not imported."
        const val HOST_DISABLED =
            "No MCP host is configured on this server (OSTROM_MCP_HOST_URL), so it cannot run a stdio server."

        fun strangers(ids: String) = "These TeamCode members are mapped to ids that are not members of this " +
            "organization: $ids. Map them to members of the organization, or leave them unmapped. Nothing was imported."
    }
}

private const val BEYOND = "+300000-01-01T00:00:00Z"
