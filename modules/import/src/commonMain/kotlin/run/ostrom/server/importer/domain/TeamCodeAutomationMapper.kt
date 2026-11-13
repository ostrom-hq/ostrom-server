// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.domain

import run.ostrom.server.automations.domain.AutomationAction
import run.ostrom.server.automations.domain.AutomationDefinition
import run.ostrom.server.automations.domain.AutomationTrigger
import run.ostrom.server.automations.domain.AutomationVariable
import run.ostrom.server.automations.domain.Catchup
import run.ostrom.server.automations.domain.ImportedAutomation
import run.ostrom.server.automations.domain.Overlap
import run.ostrom.server.automations.domain.TriggerKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * A TeamCode automation as an Ostrom one, or why it is not imported.
 *
 * @property automation The automation, or null when it is not imported.
 * @property notes What the report says besides: what is carried differently, or why it is not imported.
 */
data class MappedAutomation(val automation: ImportedAutomation?, val notes: List<String>)

/**
 * Maps a TeamCode automation to an Ostrom one (docs/decisions/workflows-one-system.md, sections 3 and 5): the owner
 * through the member map, the workflow action's workflow rewritten to the imported one, the profile's first repository
 * as `action.repository`, the overlap `queue` as `skip` until Ostrom queues. It is armed only by the cutover run, as
 * TeamCode had it; otherwise a new one lands paused and an existing one keeps its state. Pure.
 */
object TeamCodeAutomationMapper {
    /**
     * Maps one automation.
     *
     * @param automation TeamCode's automation.
     * @param owner The Ostrom member its owner is mapped to, or null.
     * @param profiles TeamCode's profiles, by id.
     * @param cutover Whether this is the cutover run.
     * @return The automation, or the reason it is not imported.
     */
    fun map(
        automation: TeamCodeAutomation,
        owner: Uuid?,
        profiles: Map<String, TeamCodeProfile>,
        cutover: Boolean,
    ): MappedAutomation {
        val trigger = TriggerKind.entries.firstOrNull { it.wire == automation.triggerKind }
        val refusal = when {
            owner == null -> "No Ostrom member is mapped to its owner."
            trigger == null ->
                "TeamCode's trigger ${automation.triggerKind.take(MAX_KIND)} has no equivalent in Ostrom."
            automation.actionKind !in ACTIONS ->
                "TeamCode's ${automation.actionKind.take(MAX_KIND)} action has no equivalent in Ostrom."
            else -> null
        }
        if (owner == null || trigger == null || refusal != null) return MappedAutomation(null, listOfNotNull(refusal))
        val variables = try {
            automation.variables.map { lenient.decodeFromJsonElement(AutomationVariable.serializer(), it) }
        } catch (expected: SerializationException) {
            // Never the parser's message: it may quote a variable.
            return MappedAutomation(null, listOf("Its variables could not be read."))
        }
        val notes = mutableListOf<String>()
        val repository = automation.profileId?.let { repositoryOf(it, profiles, "Its action", notes) }
        val workflowId = automation.workflowId?.takeIf { automation.actionKind == WORKFLOW_ACTION }
        val action = if (workflowId == null) {
            AutomationAction(
                prompt = automation.prompt,
                model = automation.model,
                effort = automation.effort,
                maxRuntimeMinutes = automation.maxRuntimeMinutes ?: AutomationAction.DEFAULT_RUNTIME_MINUTES,
                repository = repository,
            )
        } else {
            AutomationAction(
                prompt = "",
                workflowId = TeamCodeWorkflowMapper.idOf(workflowId),
                inputs = automation.workflowInputs,
                repository = repository,
            )
        }
        if (automation.overlap == QUEUE) notes += QUEUED
        if (trigger == TriggerKind.WEBHOOK) notes += WEBHOOK
        val definition = AutomationDefinition(
            name = automation.name,
            trigger = AutomationTrigger(
                kind = trigger,
                cron = automation.cron,
                timezone = automation.timezone,
                catchup = Catchup.entries.firstOrNull { it.wire == automation.catchup } ?: Catchup.ONCE,
                catchupGraceMinutes = automation.catchupGraceMinutes ?: AutomationTrigger.DEFAULT_GRACE_MINUTES,
            ),
            action = action,
            variables = variables,
            samplePayload = automation.samplePayload as? JsonObject,
            overlap = if (automation.overlap == ALLOW) Overlap.ALLOW else Overlap.SKIP,
            maxRunsPerDay = automation.maxRunsPerDay ?: AutomationDefinition.DEFAULT_MAX_RUNS_PER_DAY,
        )
        val imported = ImportedAutomation(
            sourceKey = "teamcode/${automation.id}",
            owner = owner.toString(),
            definition = definition,
            enabled = automation.enabled.takeIf { cutover },
        )
        return MappedAutomation(imported, notes)
    }

    private val lenient = Json { ignoreUnknownKeys = true }
}

private const val WORKFLOW_ACTION = "workflow"
private val ACTIONS = setOf("cloud", WORKFLOW_ACTION)
private const val QUEUE = "queue"
private const val ALLOW = "allow"
private const val MAX_KIND = 40
private const val QUEUED = "Its overlap queue is skip in Ostrom: a trigger while a run works is skipped, not queued."
private const val WEBHOOK = "A webhook gets a new URL and secret in Ostrom: rotate its secret there (it is shown " +
    "once) and point the sender to the new URL."
