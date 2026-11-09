// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer

import run.ostrom.server.importer.domain.ImportAction
import run.ostrom.server.importer.domain.ImportItem
import run.ostrom.server.importer.domain.ImportKind
import run.ostrom.server.importer.domain.ImportReport
import run.ostrom.server.importer.domain.TeamCodeAssignment
import run.ostrom.server.importer.domain.TeamCodeExport
import run.ostrom.server.importer.domain.TeamCodeImportPlanner
import run.ostrom.server.importer.domain.TeamCodeMcpServer
import run.ostrom.server.importer.domain.TeamCodeMember
import run.ostrom.server.importer.domain.TeamCodeStdio
import run.ostrom.server.importer.domain.ToolMode
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.secrets.domain.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TeamCodeImportPlannerTest {
    private val lena = Uuid.parse("0000000a-0000-0000-0000-0000000000a1")
    private val vera = Uuid.parse("0000000a-0000-0000-0000-0000000000b1")

    private val export = TeamCodeExport(
        members = listOf(TeamCodeMember("m1", "lena"), TeamCodeMember("m2", "vera"), TeamCodeMember("m3", "ghost")),
        servers = listOf(
            TeamCodeMcpServer("v-ro", "grafana", "read only", url = "https://g.example.com/ro"),
            TeamCodeMcpServer(
                "v-full",
                "grafana",
                "full",
                url = "https://g.example.com/mcp",
                toolMode = ToolMode.DENY,
                tools = listOf("update_dashboard"),
            ),
            TeamCodeMcpServer(
                "v-db",
                "MySQL Production",
                "default",
                url = "https://db.example.com/mcp",
                toolMode = ToolMode.ALLOW,
                tools = listOf("mysql_query"),
            ),
        ),
        assignments = listOf(
            TeamCodeAssignment("m2", "v-ro"),
            TeamCodeAssignment("m1", "v-full"),
            TeamCodeAssignment("m2", "v-full", SecretValue("own")),
            TeamCodeAssignment("m3", "v-db"),
            TeamCodeAssignment("m1", "v-gone"),
            TeamCodeAssignment("m9", "v-db"),
        ),
    )

    private val plan = TeamCodeImportPlanner.plan(export, mapOf("m1" to lena, "m2" to vera))

    private fun server(id: String, target: String, variant: String = "default", perMember: Boolean = false) =
        TeamCodeMcpServer(id, target, variant, perMember = perMember, url = "https://$id.example.com/mcp")

    private fun names(export: TeamCodeExport, recorded: Map<String, String> = emptyMap()) =
        TeamCodeImportPlanner.plan(export, emptyMap(), recorded).servers.associate { it.source.id to it.name }

    @Test
    fun theFirstVariantByNameKeepsTheSlotNameAndTheOthersAreSuffixed() {
        assertEquals(listOf("grafana", "mysql-production", "grafana.read-only"), plan.servers.map { it.name })
        assertEquals(listOf(true, true, false), plan.servers.map { it.primary })
        assertEquals("grafana/full", plan.servers[0].label)
    }

    @Test
    fun howManyMembersUseAVariantNeverRenamesIt() {
        val reassigned = export.copy(assignments = List(5) { TeamCodeAssignment("m$it", "v-ro") })
        assertEquals(names(export), names(reassigned))
    }

    @Test
    fun aVariantImportedBeforeKeepsItsNameWhenAVariantIsAdded() {
        val recorded = mapOf("v-full" to "grafana", "v-ro" to "grafana.read-only")
        val added = export.copy(servers = export.servers + server("v-aaa", "grafana", "aaa"))
        val named = names(added, recorded)
        assertEquals("grafana", named["v-full"])
        assertEquals("grafana.read-only", named["v-ro"])
        assertEquals("grafana.aaa", named["v-aaa"], "The newcomer sorts first but finds the slot's name taken")
    }

    @Test
    fun aRecordedSuffixedNameIsKeptAndTheSlotNameGoesToTheFirstOther() {
        val named = names(export, mapOf("v-full" to "grafana.full"))
        assertEquals("grafana.full", named["v-full"])
        assertEquals("grafana", named["v-ro"])
    }

    @Test
    fun aRecordedNameOfAnotherSlotIsNotKept() {
        assertEquals(names(export), names(export, mapOf("v-full" to "loki", "v-ro" to "loki.read-only")))
    }

    @Test
    fun secretNamesAreUpperCaseWithUnderscores() {
        assertEquals("TEAMCODE_GRAFANA_READ_ONLY", plan.servers[2].secretName)
        assertEquals("TEAMCODE_MYSQL_PRODUCTION_DSN", plan.servers[1].variableSecretName("DSN"))
    }

    @Test
    fun aServerClaimsOnlyTheSecretsItWrites() {
        val stdio = TeamCodeStdio(StdioLauncher.NPM, "x@1.0.0", secretEnv = mapOf("TOKEN" to SecretValue("t-value")))
        val servers = listOf(
            server("http", "http").copy(sharedSecret = SecretValue("s-value")),
            server("bare", "bare"),
            server("own", "own", perMember = true).copy(sharedSecret = SecretValue("ignored")),
            server("cmd", "cmd").copy(url = null, stdio = stdio, sharedSecret = SecretValue("ignored")),
            server("cmdown", "cmdown", perMember = true).copy(url = null, stdio = stdio),
        )
        val planned = TeamCodeImportPlanner.plan(TeamCodeExport(servers = servers), emptyMap()).servers
            .associateBy { it.source.id }
        assertEquals(listOf("TEAMCODE_HTTP"), planned.getValue("http").secretNames)
        assertEquals(emptyList(), planned.getValue("bare").secretNames)
        assertEquals(listOf("TEAMCODE_OWN"), planned.getValue("own").secretNames)
        assertTrue(planned.getValue("own").carriesMemberCredential)
        assertNull(planned.getValue("own").sharedCredential)
        assertEquals(listOf("TEAMCODE_CMD_TOKEN"), planned.getValue("cmd").secretNames)
        assertNull(planned.getValue("cmd").sharedCredential)
        assertEquals(listOf("TEAMCODE_CMDOWN_TOKEN"), planned.getValue("cmdown").secretNames)
        assertFalse(planned.getValue("cmdown").carriesMemberCredential, "A command's credentials are its variables")
    }

    @Test
    fun anAllowListBecomesTheAssignmentPolicyAndADenyListPolicies() {
        val (grafana, mysql, readOnly) = plan.servers
        assertNull(grafana.allowedTools)
        assertEquals(listOf("update_dashboard"), grafana.deniedTools)
        assertEquals(setOf("mysql_query"), mysql.allowedTools)
        assertEquals(emptyList(), mysql.deniedTools)
        assertNull(readOnly.allowedTools)
        assertEquals(emptyList(), readOnly.deniedTools)
    }

    @Test
    fun mappedMembersAreAssignedWithTheirOwnCredential() {
        assertEquals(
            listOf("grafana.read-only" to vera, "grafana" to lena, "grafana" to vera),
            plan.assignments.map { it.server.name to it.memberId },
        )
        assertEquals(SecretValue("own"), plan.assignments[2].memberSecret)
        assertNull(plan.assignments[1].memberSecret)
    }

    @Test
    fun unmappedMembersAndUnknownVariantsAreSkippedWithTheirReason() {
        assertEquals(
            listOf(
                skipped("mysql-production/ghost", "No Ostrom member is mapped to ghost."),
                skipped("v-gone", "No MCP variant with this id in the export."),
                skipped("mysql-production/m9", "No Ostrom member is mapped to m9."),
            ),
            plan.skipped,
        )
    }

    @Test
    fun namesKeepDigitsDashesAndUnderscores() {
        val server = TeamCodeMcpServer("v", "-K8s_ops-2", "x", url = "https://k.example.com/mcp")
        val planned = TeamCodeImportPlanner.plan(TeamCodeExport(servers = listOf(server)), emptyMap()).servers.single()
        assertEquals("k8s_ops-2", planned.name)
        assertEquals("TEAMCODE_K8S_OPS_2", planned.secretName)
        assertNull(planned.conflict)
    }

    @Test
    fun slotsTheAgentWouldSeeAsOneKeepTheFirstBySlotName() {
        val servers = listOf(server("b", "grafana"), server("a", "Grafana"))
        val planned = TeamCodeImportPlanner.plan(TeamCodeExport(servers = servers), emptyMap()).servers
        assertEquals(listOf("a", "b"), planned.map { it.source.id }, "Grafana sorts before grafana")
        assertNull(planned[0].conflict)
        assertEquals("The agent would see it as TeamCode's Grafana/default, imported as grafana.", planned[1].conflict)
    }

    @Test
    fun aDotAndAnUnderscoreAreOneNameToTheAgent() {
        val servers = listOf(server("a", "a", "a"), server("ab", "a", "b"), server("a_b", "a_b"))
        val planned = TeamCodeImportPlanner.plan(TeamCodeExport(servers = servers), emptyMap()).servers
            .associateBy { it.source.id }
        assertEquals("a.b", planned.getValue("ab").name)
        assertNull(planned.getValue("a_b").conflict, "The slot's own name comes first")
        assertEquals(
            "The agent would see it as TeamCode's a_b/default, imported as a_b.",
            planned.getValue("ab").conflict,
        )
    }

    @Test
    fun serversWritingTheSameSecretKeepTheFirst() {
        val token = SecretValue("token-value")
        val servers = listOf(
            server("dash", "a-b").copy(sharedSecret = token),
            server("under", "a_b").copy(sharedSecret = token),
            server("g", "grafana"),
            server("gp", "grafana", "prod").copy(sharedSecret = token),
            server("slot", "grafana-prod").copy(sharedSecret = token),
        )
        val planned = TeamCodeImportPlanner.plan(TeamCodeExport(servers = servers), emptyMap()).servers
            .associateBy { it.source.id }
        assertNull(planned.getValue("dash").conflict)
        assertEquals(
            "Its secret TEAMCODE_A_B would be the one of TeamCode's a-b/default, imported as a-b.",
            planned.getValue("under").conflict,
        )
        assertNull(planned.getValue("slot").conflict, "A slot's own name comes before another slot's variant")
        assertEquals(
            "Its secret TEAMCODE_GRAFANA_PROD would be the one of TeamCode's grafana-prod/default, " +
                "imported as grafana-prod.",
            planned.getValue("gp").conflict,
        )
    }

    @Test
    fun aCommandVariableCanCollideWithAnotherServersCredential() {
        val stdio = TeamCodeStdio(StdioLauncher.PYPI, "m==1.0", secretEnv = mapOf("DSN" to SecretValue("dsn-value")))
        val servers = listOf(
            server("cmd", "mysql").copy(url = null, stdio = stdio),
            server("http", "mysql-dsn").copy(sharedSecret = SecretValue("other-value")),
        )
        val planned = TeamCodeImportPlanner.plan(TeamCodeExport(servers = servers), emptyMap()).servers
            .associateBy { it.source.id }
        assertNull(planned.getValue("cmd").conflict)
        assertEquals(
            "Its secret TEAMCODE_MYSQL_DSN would be the one of TeamCode's mysql/default, imported as mysql.",
            planned.getValue("http").conflict,
        )
    }

    @Test
    fun aServerImportedBeforeWinsACollisionOverANewcomer() {
        val servers = listOf(server("new", "Grafana"), server("old", "grafana"))
        val planned = TeamCodeImportPlanner.plan(
            TeamCodeExport(servers = servers),
            emptyMap(),
            mapOf("old" to "grafana"),
        )
            .servers.associateBy { it.source.id }
        assertNull(planned.getValue("old").conflict)
        assertEquals(
            "The agent would see it as TeamCode's grafana/default, imported as grafana.",
            planned.getValue("new").conflict,
        )
    }

    @Test
    fun theAssignmentsOfAServerInConflictArePlannedToBeReported() {
        val servers = listOf(server("a", "Grafana"), server("b", "grafana"))
        val export = TeamCodeExport(servers = servers, assignments = listOf(TeamCodeAssignment("m1", "b")))
        val assignment = TeamCodeImportPlanner.plan(export, mapOf("m1" to lena)).assignments.single()
        assertEquals("b", assignment.server.source.id)
        assertTrue(assignment.server.conflict != null)
    }

    private fun skipped(name: String, reason: String) =
        ImportItem(ImportKind.ASSIGNMENT, name, ImportAction.SKIPPED, reason)

    @Test
    fun theReportCountsItsLines() {
        val report = ImportReport(plan.skipped + ImportItem(ImportKind.SETTING, "agentPrefix", ImportAction.UPDATED))
        assertEquals(3, report.count(ImportAction.SKIPPED))
        assertEquals(1, report.count(ImportAction.UPDATED))
        assertEquals(0, report.count(ImportAction.CREATED))
    }
}
