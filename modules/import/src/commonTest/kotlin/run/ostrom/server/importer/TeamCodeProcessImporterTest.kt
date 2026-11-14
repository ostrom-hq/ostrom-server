// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import run.ostrom.server.automations.domain.AutomationImport
import run.ostrom.server.automations.domain.ImportAutomationsUseCase
import run.ostrom.server.automations.domain.ImportedAutomation
import run.ostrom.server.compiler.domain.usecases.ImportWorkflowsUseCase
import run.ostrom.server.compiler.domain.usecases.WorkflowImport
import run.ostrom.server.importer.domain.ImportAction
import run.ostrom.server.importer.domain.ImportItem
import run.ostrom.server.importer.domain.ImportKind
import run.ostrom.server.importer.domain.ProcessRunMode
import run.ostrom.server.importer.domain.TeamCodeAutomation
import run.ostrom.server.importer.domain.TeamCodeMember
import run.ostrom.server.importer.domain.TeamCodeProcessImporter
import run.ostrom.server.importer.domain.TeamCodeProcesses
import run.ostrom.server.importer.domain.TeamCodeWorkflow
import run.ostrom.server.importer.domain.TeamCodeWorkflowEditors
import run.ostrom.server.importer.domain.WorkflowEditorsImporter
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class TeamCodeProcessImporterTest {
    private val caller = Importing.adminA
    private val lena = Importing.memberA.memberId
    private val principal = AuthenticatedPrincipal("user-nina", PrincipalKind.USER, setOf("import:write"))

    private fun definition(calls: String? = null): JsonObject = Json.parseToJsonElement(
        """
        {"start": "a", "nodes": [
          {"id": "a", "kind": "${if (calls == null) "agent" else "workflow"}", "title": "A", "instruction": "Go",
           "workflowId": ${calls?.let { "\"$it\"" } ?: "null"}, "transitions": [{"on": "delivered"}]}
        ]}
        """,
    ).jsonObject

    private val processes = TeamCodeProcesses(
        workflows = listOf(
            TeamCodeWorkflow("w1", "Caller", 1, definition(calls = "w2")),
            TeamCodeWorkflow("w2", "Callee", 1, definition()),
            TeamCodeWorkflow("w3", "Broken", 1, JsonObject(emptyMap())),
        ),
        editors = TeamCodeWorkflowEditors("members", listOf("m1", "m3")),
        automations = listOf(
            TeamCodeAutomation(
                "a1", "m1", "Nightly", "cron", "0 21 * * *", "Europe/Paris", actionKind = "cloud", enabled = true,
            ),
            TeamCodeAutomation("a2", "m3", "Orphan", "cron", actionKind = "cloud"),
        ),
    )
    private val members = listOf(TeamCodeMember("m1", "lena"), TeamCodeMember("m3", "ghost"))
    private val memberMap = mapOf("m1" to lena)

    private class Workflows(var next: (String) -> WorkflowImport = { WorkflowImport.CREATED }) :
        ImportWorkflowsUseCase {
        val calls = mutableListOf<Triple<String, String, Boolean>>()

        override suspend fun import(orgId: Uuid, raw: JsonObject, by: String, dryRun: Boolean): WorkflowImport {
            val id = raw.getValue("id").jsonPrimitive.content
            calls += Triple(id, by, dryRun)
            return next(id)
        }
    }

    private class Automations(var next: (ImportedAutomation) -> AutomationImport = { AutomationImport.CREATED }) :
        ImportAutomationsUseCase {
        val calls = mutableListOf<Pair<ImportedAutomation, Boolean>>()

        override suspend fun import(orgId: Uuid, automation: ImportedAutomation, dryRun: Boolean): AutomationImport {
            calls += automation to dryRun
            return next(automation)
        }
    }

    private val logins = object : MemberLogins {
        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = error("Unused.")

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            mapOf(lena to "lena", caller.memberId to "nina").filterKeys { it in memberIds }
    }

    private suspend fun run(importer: TeamCodeProcessImporter, dryRun: Boolean, cutover: Boolean = false) =
        mutableListOf<ImportItem>().also {
            importer.import(caller, processes, members, memberMap, ProcessRunMode(dryRun, cutover, principal), it)
        }

    @Test
    fun workflowsCalledFirstThenTheEditorsThenTheAutomationsAreImportedAsTheImporter() = runTest {
        val workflows = Workflows()
        val automations = Automations()
        val editors = mutableListOf<List<Uuid>>()
        val importer = TeamCodeProcessImporter(
            workflows,
            automations,
            { _, by, _, change, dryRun ->
                editors += change.memberIds
                assertEquals("nina", by)
                if (dryRun) ImportAction.PLANNED else ImportAction.UPDATED
            },
            logins,
        )
        val skippedBroken = ImportItem(
            ImportKind.WORKFLOW,
            "tc-w3",
            ImportAction.SKIPPED,
            "TeamCode's definition could not be read.",
        )
        val orphan = ImportItem(
            ImportKind.AUTOMATION,
            "ghost/Orphan",
            ImportAction.SKIPPED,
            "No Ostrom member is mapped to its owner.",
        )
        val unmapped = "1 of its members have no Ostrom member and are left out."
        assertEquals(
            listOf(
                ImportItem(ImportKind.WORKFLOW, "tc-w2", ImportAction.PLANNED),
                ImportItem(ImportKind.WORKFLOW, "tc-w1", ImportAction.PLANNED),
                skippedBroken,
                ImportItem(ImportKind.EDITORS, "members", ImportAction.PLANNED, unmapped),
                ImportItem(ImportKind.AUTOMATION, "lena/Nightly", ImportAction.PLANNED),
                orphan,
            ),
            run(importer, dryRun = true),
        )
        assertEquals(listOf(Triple("tc-w2", "nina", true), Triple("tc-w1", "nina", true)), workflows.calls)
        assertEquals(listOf(listOf(lena)), editors)

        workflows.next = { if (it == "tc-w2") WorkflowImport.UPDATED else WorkflowImport.UNCHANGED }
        automations.next = { AutomationImport.UPDATED }
        val items = run(importer, dryRun = false, cutover = true)
        assertEquals(
            listOf(ImportAction.UPDATED, ImportAction.UNCHANGED, ImportAction.SKIPPED),
            items.take(3).map { it.action },
        )
        assertEquals(ImportItem(ImportKind.AUTOMATION, "lena/Nightly", ImportAction.UPDATED), items[4])
        assertEquals(true, automations.calls.last().first.enabled, "The cutover arms what TeamCode ran")

        workflows.next = { WorkflowImport.CREATED }
        automations.next = { AutomationImport.UNCHANGED }
        val again = run(importer, dryRun = false)
        assertEquals(ImportAction.CREATED, again.first().action)
        assertEquals(ImportAction.UNCHANGED, again[4].action)
        automations.next = { AutomationImport.CREATED }
        assertEquals(ImportAction.CREATED, run(importer, dryRun = false)[4].action)
    }

    @Test
    fun aRefusalIsReportedAndAServerWithoutTheModulesSkipsWithTheReason() = runTest {
        val refused = TeamCodeProcessImporter(
            Workflows { throw OstromException(ErrorKind.CONFLICT, "workflow_name_taken: another workflow.") },
            Automations { throw OstromException(ErrorKind.INVALID_REQUEST, "action.workflowId: invalid id.") },
            { _, _, _, _, _ -> throw OstromException(ErrorKind.FORBIDDEN, "Owners only.") },
            null,
        )
        val items = run(refused, dryRun = false)
        assertEquals("workflow_name_taken: another workflow.", items.first().reason)
        assertEquals(ImportItem(ImportKind.EDITORS, "members", ImportAction.SKIPPED, "Owners only."), items[3])
        assertEquals("action.workflowId: invalid id.", items[4].reason)

        val bare = run(TeamCodeProcessImporter(null, null, null, null), dryRun = false)
        assertEquals(
            listOf(
                "This server keeps no workflows.",
                "This server keeps no workflows.",
                "TeamCode's definition could not be read.",
                "This server keeps no workflows.",
                "This server runs no automations.",
                "No Ostrom member is mapped to its owner.",
            ),
            bare.map { it.reason },
        )
        assertEquals(setOf(ImportAction.SKIPPED), bare.map { it.action }.toSet())

        val quiet = mutableListOf<ImportItem>()
        val editorsOnly = TeamCodeProcesses(editors = TeamCodeWorkflowEditors("admins"))
        TeamCodeProcessImporter(null, null, { _, _, _, _, _ -> ImportAction.UNCHANGED }, null)
            .import(caller, editorsOnly, emptyList(), emptyMap(), ProcessRunMode(false, false, principal), quiet)
        assertEquals(listOf(ImportItem(ImportKind.EDITORS, "admins", ImportAction.UNCHANGED)), quiet)
        val none = mutableListOf<ImportItem>()
        TeamCodeProcessImporter(null, null, null, null)
            .import(caller, TeamCodeProcesses(), emptyList(), emptyMap(), ProcessRunMode(false, false, principal), none)
        assertEquals(emptyList(), none)
    }

    @Test
    fun theFirstRunReportsTheNotesOfAWorkflowItImports() = runTest {
        val noted = TeamCodeProcesses(
            workflows = listOf(
                TeamCodeWorkflow(
                    "w9",
                    "Noted",
                    1,
                    Json.parseToJsonElement(
                        """{"start": "a", "nodes": [{"id": "a", "kind": "agent", "title": "A", "instruction": "Go",
                        "profileId": "p-gone", "transitions": [{"on": "delivered"}]}]}""",
                    ).jsonObject,
                ),
            ),
        )
        val items = mutableListOf<ImportItem>()
        TeamCodeProcessImporter(Workflows(), null, null, logins)
            .import(caller, noted, emptyList(), emptyMap(), ProcessRunMode(false, false, principal), items)
        assertEquals(
            ImportItem(
                ImportKind.WORKFLOW,
                "tc-w9",
                ImportAction.CREATED,
                "Node a: its profile names no repository, so it works in none.",
            ),
            items.single(),
        )
    }
}
