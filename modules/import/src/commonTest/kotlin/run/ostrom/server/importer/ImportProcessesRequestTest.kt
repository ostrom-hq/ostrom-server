// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import run.ostrom.server.importer.domain.ImportAction
import run.ostrom.server.importer.domain.ImportItem
import run.ostrom.server.importer.domain.ImportKind
import run.ostrom.server.importer.domain.ImportReport
import run.ostrom.server.importer.domain.TeamCodeAutomation
import run.ostrom.server.importer.domain.TeamCodeProfile
import run.ostrom.server.importer.domain.TeamCodeWorkflow
import run.ostrom.server.importer.domain.TeamCodeWorkflowEditors
import run.ostrom.server.importer.presentation.TeamCodeImportRequest
import run.ostrom.server.importer.presentation.toDto
import run.ostrom.server.importer.presentation.toExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ImportProcessesRequestTest {
    // The JSON `ostrom import teamcode` sends for the workflows, their editing rule and the automations.
    private val body = """
        {
          "workflows": [{
            "id": "w1", "name": "Fix", "version": 2, "updatedById": "m1", "createdAt": 1700000000000,
            "updatedAt": 1700000500000,
            "definition": {"start": "a", "nodes": [{"id": "a", "kind": "agent", "title": "A", "instruction": "secret plan"}]}
          }],
          "workflowEditors": {"mode": "members", "memberIds": ["m1"]},
          "automations": [
            {
              "id": "a1", "ownerId": "m1", "name": "Nightly", "triggerKind": "cron", "cron": "0 21 * * *",
              "timezone": "Europe/Paris", "catchup": "once", "catchupGraceMinutes": 60, "actionKind": "cloud",
              "profileId": "p1", "model": "opus", "effort": "high", "maxRuntimeMinutes": 30,
              "prompt": "secret prompt", "variables": [], "overlap": "skip", "maxRunsPerDay": 5,
              "enabled": true, "version": 3
            },
            {
              "id": "a2", "ownerId": "m1", "name": "Hook", "triggerKind": "webhook", "actionKind": "workflow",
              "workflowId": "w1", "workflowInputs": {"ticket": "{{t}}"}, "samplePayload": {"t": "secret payload"},
              "variables": [{"name": "t", "path": "$.t"}], "enabled": false
            }
          ],
          "profiles": [{"id": "p1", "name": "API", "repositories": ["acme/api"], "setupCommands": 1}],
          "memberMap": {}
        }
    """

    @Test
    fun theCliSendsTheWorkflowsEditorsAutomationsAndProfilesTheImportReads() {
        val request = Json.decodeFromString(TeamCodeImportRequest.serializer(), body)
        val processes = request.toExport().processes
        val workflow = processes.workflows.single()
        assertEquals(
            TeamCodeWorkflow("w1", "Fix", 2, workflow.definition, "m1", 1_700_000_000_000),
            workflow,
        )
        assertEquals(TeamCodeWorkflowEditors("members", listOf("m1")), processes.editors)
        assertEquals(listOf(TeamCodeProfile("p1", "API", listOf("acme/api"), 1)), processes.profiles)
        assertEquals(
            TeamCodeAutomation(
                id = "a1",
                ownerId = "m1",
                name = "Nightly",
                triggerKind = "cron",
                cron = "0 21 * * *",
                timezone = "Europe/Paris",
                catchup = "once",
                catchupGraceMinutes = 60,
                actionKind = "cloud",
                profileId = "p1",
                model = "opus",
                effort = "high",
                maxRuntimeMinutes = 30,
                prompt = "secret prompt",
                overlap = "skip",
                maxRunsPerDay = 5,
                enabled = true,
            ),
            processes.automations.first(),
        )
        val hook = processes.automations.last()
        assertEquals("", hook.prompt)
        assertEquals(mapOf("ticket" to "{{t}}"), hook.workflowInputs)
        assertEquals(buildJsonObject { put("t", "secret payload") }, hook.samplePayload)
        assertEquals(JsonPrimitive("$.t"), (hook.variables.single() as JsonObject)["path"])

        val printed = "$request ${request.workflows} ${request.automations}"
        assertFalse("secret" in printed, "A request never prints an instruction, a prompt or a payload")
        assertEquals(null, Json.decodeFromString(TeamCodeImportRequest.serializer(), "{}").toExport().processes.editors)
    }

    @Test
    fun theReportNamesTheNewKinds() {
        val report = ImportReport(
            listOf(
                ImportItem(ImportKind.WORKFLOW, "tc-w1", ImportAction.CREATED),
                ImportItem(ImportKind.EDITORS, "members", ImportAction.SKIPPED, "Not yet."),
                ImportItem(ImportKind.AUTOMATION, "lena/Nightly", ImportAction.PLANNED),
            ),
        )
        assertEquals(listOf("workflow", "editors", "automation"), report.toDto().items.map { it.kind })
    }
}
