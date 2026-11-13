// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A row of TeamCode's `workflows` table.
 *
 * @property id TeamCode's id; the workflow is `tc-<id>` in Ostrom.
 * @property name Its name.
 * @property version TeamCode's version, kept in the workflow's `sources`.
 * @property definition The definition as TeamCode stores it (`start`, `sessionMode`, `inputs`, `nodes`, `limits`,
 *   `description`).
 * @property updatedById The TeamCode member who saved it last.
 * @property createdAt When it was created, epoch milliseconds: the workflow's `foundAt`.
 */
data class TeamCodeWorkflow(
    val id: String,
    val name: String,
    val version: Int,
    val definition: JsonObject,
    val updatedById: String? = null,
    val createdAt: Long? = null,
)

/**
 * TeamCode's `workflow_settings` row: who edits the workflows.
 *
 * @property mode `all`, `admins` or `members`.
 * @property memberIds The TeamCode members who edit, in mode `members`.
 */
data class TeamCodeWorkflowEditors(val mode: String, val memberIds: List<String> = emptyList())

/**
 * A row of TeamCode's `automations` table, without its secrets.
 *
 * @property id TeamCode's id.
 * @property ownerId The TeamCode member who owns it.
 * @property name Its name.
 * @property triggerKind `cron` or `webhook`.
 * @property cron The schedule.
 * @property timezone The schedule's zone.
 * @property catchup `once` or `skip`.
 * @property catchupGraceMinutes How late a missed slot may still run.
 * @property actionKind `cloud`, `workflow` or `remote`.
 * @property profileId The cloud profile the session or the workflow run works in.
 * @property model The model.
 * @property effort The effort.
 * @property maxRuntimeMinutes The longest a session works.
 * @property prompt The prompt (empty for a workflow action).
 * @property variables The webhook variables, in the shape Ostrom's automations read them.
 * @property samplePayload The sample payload of a dry run.
 * @property overlap `skip`, `queue` or `allow`.
 * @property maxRunsPerDay The most runs a day.
 * @property enabled Whether TeamCode runs it.
 * @property workflowId The workflow a workflow action starts.
 * @property workflowInputs Its inputs' templates.
 */
data class TeamCodeAutomation(
    val id: String,
    val ownerId: String,
    val name: String,
    val triggerKind: String,
    val cron: String? = null,
    val timezone: String? = null,
    val catchup: String? = null,
    val catchupGraceMinutes: Int? = null,
    val actionKind: String,
    val profileId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxRuntimeMinutes: Int? = null,
    val prompt: String = "",
    val variables: List<JsonElement> = emptyList(),
    val samplePayload: JsonElement? = null,
    val overlap: String? = null,
    val maxRunsPerDay: Int? = null,
    val enabled: Boolean = false,
    val workflowId: String? = null,
    val workflowInputs: Map<String, String> = emptyMap(),
) {
    /** Without the prompt, the variables and the sample payload: a log line never shows them. */
    override fun toString(): String = "TeamCodeAutomation(id=$id, ownerId=$ownerId, name=$name)"
}

/**
 * The repositories of a TeamCode cloud profile's current version.
 *
 * @property id TeamCode's id.
 * @property name Its name, for the report.
 * @property repositories Its repositories (`owner/name`); the first is the `repository` of what names the profile.
 * @property setupCommands How many setup commands its recipe has; they are not carried.
 */
data class TeamCodeProfile(
    val id: String,
    val name: String? = null,
    val repositories: List<String> = emptyList(),
    val setupCommands: Int = 0,
)
