// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import dev.ostrom.server.importer.domain.ImportAction
import dev.ostrom.server.importer.domain.ImportItem
import dev.ostrom.server.importer.domain.ImportKind
import dev.ostrom.server.importer.domain.ImportReport
import dev.ostrom.server.importer.domain.TeamCodeAssignment
import dev.ostrom.server.importer.domain.TeamCodeExport
import dev.ostrom.server.importer.domain.TeamCodeImportPlanner
import dev.ostrom.server.importer.domain.TeamCodeMcpServer
import dev.ostrom.server.importer.domain.TeamCodeMember
import dev.ostrom.server.importer.domain.ToolMode
import dev.ostrom.server.secrets.domain.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class TeamCodeImportPlannerTest {
    private val lyes = Uuid.parse("0000000a-0000-0000-0000-0000000000a1")
    private val vivien = Uuid.parse("0000000a-0000-0000-0000-0000000000b1")

    private val export = TeamCodeExport(
        members = listOf(TeamCodeMember("m1", "lyes"), TeamCodeMember("m2", "vivien"), TeamCodeMember("m3", "ghost")),
        servers = listOf(
            TeamCodeMcpServer("v-ro", "grafana", "Read only", url = "https://g.example.com/ro"),
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
            TeamCodeAssignment("m1", "v-full"),
            TeamCodeAssignment("m2", "v-full", SecretValue("own")),
            TeamCodeAssignment("m2", "v-ro"),
            TeamCodeAssignment("m3", "v-db"),
            TeamCodeAssignment("m1", "v-gone"),
            TeamCodeAssignment("m9", "v-db"),
        ),
    )

    private val plan = TeamCodeImportPlanner.plan(export, mapOf("m1" to lyes, "m2" to vivien))

    @Test
    fun theMostAssignedVariantKeepsTheSlotNameAndTheOthersAreSuffixed() {
        assertEquals(listOf("grafana", "mysql-production", "grafana.read-only"), plan.servers.map { it.name })
        assertEquals(listOf(true, true, false), plan.servers.map { it.primary })
    }

    @Test
    fun secretNamesAreUpperCaseWithUnderscores() {
        assertEquals("TEAMCODE_GRAFANA_READ_ONLY", plan.servers[2].secretName)
        assertEquals("TEAMCODE_MYSQL_PRODUCTION_DSN", plan.servers[1].variableSecretName("DSN"))
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
            listOf("grafana" to lyes, "grafana" to vivien, "grafana.read-only" to vivien),
            plan.assignments.map { it.server.name to it.memberId },
        )
        assertEquals(SecretValue("own"), plan.assignments[1].memberSecret)
        assertNull(plan.assignments[0].memberSecret)
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
