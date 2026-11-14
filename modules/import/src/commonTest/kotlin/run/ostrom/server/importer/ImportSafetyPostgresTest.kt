// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import run.ostrom.server.importer.ImportPostgresTest.Companion.NOT_IMPORTED
import run.ostrom.server.importer.ImportPostgresTest.Companion.NO_SHARING
import run.ostrom.server.importer.Importing.adminA
import run.ostrom.server.importer.Importing.memberA
import run.ostrom.server.importer.Importing.modules
import run.ostrom.server.importer.Importing.otherA
import run.ostrom.server.importer.domain.ImportAction.CREATED
import run.ostrom.server.importer.domain.ImportAction.SKIPPED
import run.ostrom.server.importer.domain.ImportAction.UNCHANGED
import run.ostrom.server.importer.domain.ImportAction.UPDATED
import run.ostrom.server.importer.presentation.ImportItemDto
import run.ostrom.server.importer.presentation.TeamCodeAssignmentDto
import run.ostrom.server.importer.presentation.TeamCodeImportRequest
import run.ostrom.server.importer.presentation.TeamCodeMcpServerDto
import run.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import run.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import run.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import run.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import run.ostrom.server.mcprelay.domain.McpServerPayload
import run.ostrom.server.mcprelay.domain.McpSettings
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.PutMcpSettingsUseCase
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CreateSecretPayload
import run.ostrom.server.secrets.domain.CreateSecretUseCase
import run.ostrom.server.secrets.domain.FindSecretUseCase
import run.ostrom.server.secrets.domain.RevealSecretsUseCase
import run.ostrom.server.secrets.domain.SecretValue
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the import must never do: fail open, wipe, overwrite or rename what it did not create. */
class ImportSafetyPostgresTest {
    private fun logs(tools: List<String>) = TeamCodeImportRequest(
        mcpServers = listOf(
            TeamCodeMcpServerDto(
                "v-logs", "logs", "default", url = "https://l.example.com/mcp", toolMode = "deny", tools = tools,
            ),
        ),
        assignments = listOf(TeamCodeAssignmentDto("m1", "v-logs")),
        memberMap = Importing.members,
    )

    private suspend fun TestServer.value(caller: Caller, name: String, personal: Boolean): SecretValue {
        val id = koin.get<FindSecretUseCase>()(caller, name, personal).id
        return koin.get<RevealSecretsUseCase>()(caller.orgId, listOf(id)).getValue(id)
    }

    @Test
    fun aServerMissingADenialIsNeverAssigned() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val refused = "Denying bad tool! was refused (The policy names an invalid tool.), " +
                "so this import assigns the server to no one."
            val first = report(logs(listOf("delete_all", "bad tool!")))
            assertEquals(
                listOf(
                    line("setting", "agentPrefix", UPDATED),
                    line("server", "logs", SKIPPED, refused),
                    line("assignment", "logs/${memberA.memberId}", SKIPPED, NOT_IMPORTED),
                ),
                first.items,
            )
            val created = servers().getValue("logs")
            assertEquals(emptyList(), koin.get<ListMcpAssignmentsUseCase>()(adminA, created.id), "Nobody reaches it")
            assertEquals(
                listOf("delete_all"),
                koin.get<ListArgumentPoliciesUseCase>()(adminA, created.id).map {
                    it.tool
                },
            )
            // Already there, it is not assigned either while a denial is missing.
            val again = report(logs(listOf("delete_all", "bad tool!")))
            assertEquals(line("server", "logs", SKIPPED, refused), again.items[1])
            assertEquals("https://l.example.com/mcp", servers().getValue("logs").url)
            assertEquals(emptyList(), koin.get<ListMcpAssignmentsUseCase>()(adminA, created.id))
            val fixed = report(logs(listOf("delete_all")))
            assertEquals(
                listOf(
                    line("setting", "agentPrefix", UNCHANGED),
                    line("server", "logs", UNCHANGED),
                    line("policy", "logs/delete_all", UNCHANGED),
                    line("assignment", "logs/${memberA.memberId}", CREATED),
                ),
                fixed.items,
            )
        }
    }

    @Test
    fun aMembersCredentialIsReplacedOnlyByTeamCodesOwn() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            report(Importing.team())
            val own = koin.get<FindSecretUseCase>()(memberA, "TEAMCODE_DISCORD", personal = true)
            val discordA = "discord/${memberA.memberId}"
            val withoutIt = report(Importing.team(lenaDiscordToken = null))
            assertEquals(line("assignment", discordA, UNCHANGED), withoutIt.items.single { it.name == discordA })
            assertEquals(own.id, credentialOf(memberA))
            val rotated = report(Importing.team(lenaDiscordToken = "d-lena-token-2"))
            val label = "TEAMCODE_DISCORD (personal, ${memberA.memberId})"
            assertEquals(line("secret", label, UPDATED), rotated.items.single { it.name == label })
            assertEquals(line("assignment", discordA, UNCHANGED), rotated.items.single { it.name == discordA })
            assertEquals(SecretValue("d-lena-token-2"), value(memberA, "TEAMCODE_DISCORD", personal = true))
            // A server shared in TeamCode carries no member's credential: the one kept would be refused.
            val shared = report(Importing.team(lenaDiscordToken = null, discordPerMember = false))
            assertEquals(line("server", "discord", UPDATED), shared.items.single { it.name == "discord" })
            assertEquals(line("assignment", discordA, UPDATED), shared.items.single { it.name == discordA })
            assertNull(credentialOf(memberA))
        }
    }

    private suspend fun TestServer.credentialOf(member: Caller) =
        koin.get<ListMcpAssignmentsUseCase>()(adminA, servers().getValue("discord").id)
            .single { it.memberId == member.memberId }
            .credentialSecretId

    @Test
    fun ofTwoVariantsTheAgentWouldSeeAsOneOnlyTheFirstIsImported() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val request = TeamCodeImportRequest(
                mcpServers = listOf(
                    TeamCodeMcpServerDto(
                        "lower",
                        "grafana",
                        "default",
                        url = "https://lower.example.com/mcp",
                        sharedSecret = "lower-token",
                    ),
                    TeamCodeMcpServerDto(
                        "upper",
                        "Grafana",
                        "default",
                        url = "https://upper.example.com/mcp",
                        sharedSecret = "upper-token",
                    ),
                ),
                assignments = listOf(TeamCodeAssignmentDto("m1", "lower")),
                memberMap = Importing.members,
            )
            val expected = listOf(
                line("setting", "agentPrefix", UPDATED),
                line("secret", "TEAMCODE_GRAFANA", CREATED),
                line("server", "grafana", CREATED),
                line(
                    "server",
                    "grafana",
                    SKIPPED,
                    "The agent would see it as TeamCode's Grafana/default, imported as grafana.",
                ),
                line("assignment", "grafana/${memberA.memberId}", SKIPPED, NOT_IMPORTED),
            )
            assertEquals(expected, report(request).items)
            assertEquals("https://upper.example.com/mcp", servers().getValue("grafana").url)
            val again = report(request)
            assertEquals(expected.map { it.name to it.reason }, again.items.map { it.name to it.reason })
            assertEquals(SecretValue("upper-token"), value(adminA, "TEAMCODE_GRAFANA", personal = false))
        }
    }

    @Test
    fun aTeamCodeServerNamedLikeOstromsOwnIsReportedBeforeAnythingIsWritten() =
        serverTest(modules, importOverrides()) { server ->
            with(server) {
                val request = TeamCodeImportRequest(
                    mcpServers = listOf(
                        TeamCodeMcpServerDto(
                            "own",
                            "Ostrom",
                            "default",
                            url = "https://ostrom.example.com/mcp",
                            sharedSecret = "own-token",
                        ),
                    ),
                    memberMap = Importing.members,
                )
                val refused = line(
                    "server",
                    "ostrom",
                    SKIPPED,
                    "Agents would see ostrom as ostrom, Ostrom's own MCP server: rename it in TeamCode.",
                )
                val plan = report(request, dryRun = true).items
                assertEquals(refused, plan.single { it.kind == "server" }, "The plan says it, as the run does")
                assertEquals(refused, report(request).items.single { it.kind == "server" })
                assertNull(servers()["ostrom"], "Nothing was declared")
            }
        }

    /** What an admin and a member create in the console before importing, under the names the import would use. */
    private suspend fun TestServer.createByHand() {
        val createServer = koin.get<CreateMcpServerUseCase>()
        createServer(adminA, McpServerPayload("grafana", McpTransport.HTTP, url = "https://native.example.com/mcp"))
        createServer(adminA, McpServerPayload("grafana_ro", McpTransport.HTTP, url = "https://native.example.com/ro"))
        val createSecret = koin.get<CreateSecretUseCase>()
        createSecret(adminA, CreateSecretPayload("TEAMCODE_MYSQL_MYSQL_DSN", SecretValue("native-dsn"), false))
        createSecret(memberA, CreateSecretPayload("TEAMCODE_DISCORD", SecretValue("native-own"), true))
    }

    @Test
    fun whatAnAdminCreatedByHandIsLeftAloneAndReported() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            createByHand()
            val plan = report(Importing.team(), dryRun = true)
            val report = report(Importing.team())
            val personal = "TEAMCODE_DISCORD (personal, ${memberA.memberId})"
            assertEquals(
                listOf(
                    line("setting", "agentPrefix", SKIPPED, KEPT),
                    line("server", "discord", CREATED),
                    line(
                        "server",
                        "grafana",
                        SKIPPED,
                        "A server named grafana exists and was not imported from TeamCode.",
                    ),
                    line(
                        "server",
                        "mysql",
                        SKIPPED,
                        "A secret named TEAMCODE_MYSQL_MYSQL_DSN exists and was not imported from TeamCode.",
                    ),
                    line(
                        "server",
                        "grafana.ro",
                        SKIPPED,
                        "The agent would see grafana.ro as the server grafana_ro already is.",
                    ),
                    line("assignment", "grafana/${memberA.memberId}", SKIPPED, NOT_IMPORTED),
                    line("assignment", "grafana/${otherA.memberId}", SKIPPED, NOT_IMPORTED),
                    line(
                        "secret",
                        personal,
                        SKIPPED,
                        "A secret named $personal exists and was not imported from TeamCode.",
                    ),
                    line("assignment", "discord/${memberA.memberId}", CREATED),
                    line("assignment", "discord/${otherA.memberId}", CREATED),
                    line("assignment", "grafana.ro/${otherA.memberId}", SKIPPED, NOT_IMPORTED),
                    line("assignment", "mysql/${memberA.memberId}", SKIPPED, NOT_IMPORTED),
                    line("assignment", "grafana.ro/ghost", SKIPPED, "No Ostrom member is mapped to ghost."),
                    line("account", "claude/Lena", SKIPPED, NO_SHARING),
                    line("account", "codex/Lena", SKIPPED, NO_SHARING),
                ),
                report.items,
            )
            assertEquals(report.items.map(ImportItemDto::withoutWrites), plan.items.map(ImportItemDto::withoutWrites))
            assertEquals("https://native.example.com/mcp", servers().getValue("grafana").url)
            assertEquals(SecretValue("native-dsn"), value(adminA, "TEAMCODE_MYSQL_MYSQL_DSN", personal = false))
            assertEquals(SecretValue("native-own"), value(memberA, "TEAMCODE_DISCORD", personal = true))
            assertNull(credentialOf(memberA), "The member's own secret is theirs to assign")
            assertEquals("ostrom", koin.get<GetMcpSettingsUseCase>()(adminA).agentPrefix)
        }
    }

    @Test
    fun aPrefixAnAdminChoseIsKept() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            koin.get<PutMcpSettingsUseCase>()(adminA, McpSettings("acme"))
            val report = report(logs(listOf("delete_all")))
            assertEquals(
                line("setting", "agentPrefix", SKIPPED, KEPT.replace("ostrom", "acme")),
                report.items.first(),
            )
            assertEquals("acme", koin.get<GetMcpSettingsUseCase>()(adminA).agentPrefix)
        }
    }

    @Test
    fun aVariantAddedInTeamCodeNeverRenamesTheOnesImported() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            report(Importing.team())
            val first = servers().getValue("grafana").id
            val added = TeamCodeMcpServerDto("v-aaa", "grafana", "aaa", url = "https://g.example.com/aaa")
            val report = report(Importing.team(extraServers = listOf(added)))
            val actions = report.items.filter { it.kind == "server" }.associate { it.name to it.action }
            assertEquals("unchanged", actions["grafana"], "The full variant keeps the slot's name")
            assertEquals("unchanged", actions["grafana.ro"])
            assertEquals("created", actions["grafana.aaa"], "The newcomer sorts first, yet takes a suffix")
            assertEquals(first, servers().getValue("grafana").id)
        }
    }

    private companion object {
        const val KEPT = "The agent prefix ostrom was kept: changing it would rename the tools the org already uses."
    }
}

/** A line as a dry run reports it: what would be written is planned. */
private fun ImportItemDto.withoutWrites() =
    if (action == "created" || action == "updated") copy(action = "planned") else this
