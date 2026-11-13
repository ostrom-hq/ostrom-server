// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer

import run.ostrom.server.automations.domain.AutomationAction
import run.ostrom.server.automations.domain.AutomationDefinition
import run.ostrom.server.automations.domain.AutomationTrigger
import run.ostrom.server.automations.domain.AutomationVariable
import run.ostrom.server.automations.domain.Catchup
import run.ostrom.server.automations.domain.Overlap
import run.ostrom.server.automations.domain.TriggerKind
import run.ostrom.server.automations.domain.VariableType
import run.ostrom.server.importer.domain.TeamCodeAutomation
import run.ostrom.server.importer.domain.TeamCodeAutomationMapper
import run.ostrom.server.importer.domain.TeamCodeProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TeamCodeAutomationMapperTest {
    private val lena = Uuid.parse("0000000a-0000-0000-0000-0000000000a1")
    private val profiles = listOf(TeamCodeProfile("p-api", "API", listOf("acme/api"))).associateBy { it.id }

    private val nightly = TeamCodeAutomation(
        id = "a1",
        ownerId = "m1",
        name = "Nightly",
        triggerKind = "cron",
        cron = "0 21 * * *",
        timezone = "Europe/Paris",
        catchup = "skip",
        catchupGraceMinutes = 30,
        actionKind = "cloud",
        profileId = "p-api",
        model = "opus",
        effort = "high",
        maxRuntimeMinutes = 45,
        prompt = "Summarize {{run.date}}",
        overlap = "queue",
        maxRunsPerDay = 3,
        enabled = true,
    )

    private val ticket = TeamCodeAutomation(
        id = "a2",
        ownerId = "m1",
        name = "Ticket",
        triggerKind = "webhook",
        actionKind = "workflow",
        workflowId = "w1",
        workflowInputs = mapOf("ticket" to "{{ticket}}"),
        variables = listOf(Json.parseToJsonElement("""{"name": "ticket", "path": "$.t", "type": "int", "x": 1}""")),
        samplePayload = buildJsonObject { put("t", 7) },
        overlap = "allow",
        enabled = true,
    )

    @Test
    fun aSessionAutomationKeepsItsScheduleAndActionWithTheProfilesRepositoryAndLandsPaused() {
        val mapped = TeamCodeAutomationMapper.map(nightly, lena, profiles, cutover = false)
        val automation = assertNotNull(mapped.automation)
        assertEquals("teamcode/a1", automation.sourceKey)
        assertEquals(lena.toString(), automation.owner)
        assertNull(automation.enabled, "A new one lands paused, an existing one keeps its state")
        assertEquals(
            AutomationDefinition(
                name = "Nightly",
                trigger = AutomationTrigger(TriggerKind.CRON, "0 21 * * *", "Europe/Paris", Catchup.SKIP, 30),
                action = AutomationAction(
                    prompt = "Summarize {{run.date}}",
                    model = "opus",
                    effort = "high",
                    maxRuntimeMinutes = 45,
                    repository = "acme/api",
                ),
                overlap = Overlap.SKIP,
                maxRunsPerDay = 3,
            ),
            automation.definition,
        )
        assertEquals(
            listOf("Its overlap queue is skip in Ostrom: a trigger while a run works is skipped, not queued."),
            mapped.notes,
        )
        assertEquals(true, TeamCodeAutomationMapper.map(nightly, lena, profiles, cutover = true).automation?.enabled)
    }

    @Test
    fun aWorkflowWebhookStartsTheImportedWorkflowAndSaysToRotateItsSecret() {
        val mapped = TeamCodeAutomationMapper.map(ticket, lena, profiles, cutover = true)
        val definition = assertNotNull(mapped.automation).definition
        assertEquals(
            AutomationAction("", workflowId = "tc-w1", inputs = mapOf("ticket" to "{{ticket}}")),
            definition.action,
        )
        assertEquals(AutomationTrigger(TriggerKind.WEBHOOK), definition.trigger)
        assertEquals(listOf(AutomationVariable("ticket", "$.t", VariableType.INT)), definition.variables)
        assertEquals(buildJsonObject { put("t", 7) }, definition.samplePayload)
        assertEquals(Overlap.ALLOW, definition.overlap)
        assertEquals(AutomationDefinition.DEFAULT_MAX_RUNS_PER_DAY, definition.maxRunsPerDay)
        assertTrue(mapped.notes.single().startsWith("A webhook gets a new URL and secret in Ostrom"))
        assertTrue(mapped.automation?.enabled == true)

        val bare = ticket.copy(workflowId = null, samplePayload = JsonPrimitive("text"), prompt = "Go", overlap = null)
        val session = assertNotNull(TeamCodeAutomationMapper.map(bare, lena, profiles, false).automation).definition
        assertNull(session.action.workflowId, "A workflow action without its workflow is a session's, refused at save")
        assertEquals(AutomationAction.DEFAULT_RUNTIME_MINUTES, session.action.maxRuntimeMinutes)
        assertNull(session.samplePayload)
        assertEquals(Overlap.SKIP, session.overlap)
        val cloud = nightly.copy(workflowId = "w1", catchup = null, catchupGraceMinutes = null, overlap = "skip")
        val kept = assertNotNull(TeamCodeAutomationMapper.map(cloud, lena, profiles, false).automation).definition
        assertNull(kept.action.workflowId, "Only a workflow action starts a workflow")
        assertEquals(Catchup.ONCE, kept.trigger.catchup)
        assertEquals(AutomationTrigger.DEFAULT_GRACE_MINUTES, kept.trigger.catchupGraceMinutes)
    }

    @Test
    fun anUnmappedOwnerAnUnknownTriggerOrActionAndUnreadableVariablesAreNotImported() {
        val refusals = listOf(
            TeamCodeAutomationMapper.map(nightly, null, profiles, false),
            TeamCodeAutomationMapper.map(nightly.copy(triggerKind = "email"), lena, profiles, false),
            TeamCodeAutomationMapper.map(nightly.copy(actionKind = "remote"), lena, profiles, false),
            TeamCodeAutomationMapper.map(
                ticket.copy(variables = listOf(JsonPrimitive("secret-looking"))),
                lena,
                profiles,
                false,
            ),
        )
        assertEquals(
            listOf(
                "No Ostrom member is mapped to its owner.",
                "TeamCode's trigger email has no equivalent in Ostrom.",
                "TeamCode's remote action has no equivalent in Ostrom.",
                "Its variables could not be read.",
            ),
            refusals.map { it.notes.single() },
        )
        assertTrue(refusals.all { it.automation == null })
        assertFalse("secret-looking" in refusals.last().notes.single())
        assertFalse("Summarize" in nightly.toString(), "A log line never shows the prompt")
    }
}
