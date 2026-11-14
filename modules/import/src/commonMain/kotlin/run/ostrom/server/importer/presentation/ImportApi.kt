// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.presentation

import run.ostrom.server.importer.domain.ImportTeamCodeUseCase
import run.ostrom.server.secrets.domain.CallerResolver
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `/api/v1/import/teamcode`: imports a TeamCode team into the caller's org (an addition to the contract, proposed in
 * ostrom-core).
 *
 * @property dryRun Reports the plan without writing anything.
 * @property cutover The final run, once TeamCode is stopped (owners only, recorded in the audit log): it also revives
 *   the shared accounts waiting for a reconnection, testing TeamCode's tokens first, and hands over those waiting for
 *   their handover.
 * @property accessOnly TeamCode's `--access-only` trial: the shared accounts serve with TeamCode's access tokens and
 *   are never renewed by Ostrom.
 */
@Serializable
@Resource("/api/v1/import/teamcode")
class TeamCodeImportApi(val dryRun: Boolean = false, val cutover: Boolean = false, val accessOnly: Boolean = false)

/** The scope a console token needs to import (a browser session has every user scope). */
const val IMPORT_SCOPE: String = "import:write"

/**
 * What the import routes need.
 *
 * @property callers Resolves the caller's org and role.
 * @property importTeamCode The import.
 */
data class ImportRoutesDependencies(val callers: CallerResolver, val importTeamCode: ImportTeamCodeUseCase)

/**
 * A TeamCode member.
 *
 * @property id TeamCode's id.
 * @property username TeamCode's username.
 */
@Serializable
data class TeamCodeMemberDto(val id: String, val username: String)

/**
 * A command run by the MCP host.
 *
 * @property launcher `npm` or `pypi`.
 * @property packageName The pinned package: the first argument of TeamCode's `args`, like `McpStdioSpec.packageName`.
 * @property args Its arguments.
 * @property secretEnv Its secret variables, values in clear (sent once, over TLS).
 */
@Serializable
data class TeamCodeStdioDto(
    val launcher: String,
    val packageName: String,
    val args: List<String> = emptyList(),
    val secretEnv: Map<String, String> = emptyMap(),
) {
    /** Without the secret values: a log line or an error message never shows them. */
    override fun toString(): String =
        "TeamCodeStdioDto(launcher=$launcher, packageName=$packageName, args=$args, secretEnv=${secretEnv.keys})"
}

/**
 * An MCP variant of TeamCode.
 *
 * @property id TeamCode's variant id.
 * @property target The slot (the name agents see).
 * @property variant The variant's name.
 * @property perMember Whether each member brings their own credential.
 * @property url The HTTPS endpoint.
 * @property credentialHeader The credential header, `Authorization: Bearer` when absent.
 * @property sharedSecret The shared credential, in clear.
 * @property stdio The command, for a server run by the MCP host.
 * @property toolMode `allow` or `deny`, absent for every tool.
 * @property tools The tool policy's list.
 */
@Serializable
data class TeamCodeMcpServerDto(
    val id: String,
    val target: String,
    val variant: String,
    val perMember: Boolean = false,
    val url: String? = null,
    val credentialHeader: String? = null,
    val sharedSecret: String? = null,
    val stdio: TeamCodeStdioDto? = null,
    val toolMode: String? = null,
    val tools: List<String> = emptyList(),
) {
    /** Without the shared credential: a log line or an error message never shows it. */
    override fun toString(): String = "TeamCodeMcpServerDto(id=$id, target=$target, variant=$variant, " +
        "perMember=$perMember, url=$url, credentialHeader=$credentialHeader, sharedSecret=$REDACTED, stdio=$stdio, " +
        "toolMode=$toolMode, tools=$tools)"
}

/**
 * A member's assignment.
 *
 * @property memberId TeamCode's member id.
 * @property serverId TeamCode's variant id.
 * @property memberSecret The member's own credential, in clear.
 */
@Serializable
data class TeamCodeAssignmentDto(val memberId: String, val serverId: String, val memberSecret: String? = null) {
    /** Without the member's credential: a log line or an error message never shows it. */
    override fun toString(): String =
        "TeamCodeAssignmentDto(memberId=$memberId, serverId=$serverId, memberSecret=$REDACTED)"
}

/**
 * A shared subscription account.
 *
 * @property provider `claude` or `codex`.
 * @property providerAccountId The provider's account id.
 * @property label Its display name.
 * @property email Its e-mail.
 * @property accessToken The OAuth access token.
 * @property refreshToken The OAuth refresh token.
 * @property expiresAt When the access token expires (ISO 8601).
 * @property disabled Whether TeamCode had it disabled.
 */
@Serializable
data class TeamCodeAccountDto(
    val provider: String,
    val providerAccountId: String,
    val label: String,
    val email: String? = null,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val disabled: Boolean = false,
) {
    /** Without the tokens: a log line or an error message never shows them. */
    override fun toString(): String = "TeamCodeAccountDto(provider=$provider, " +
        "providerAccountId=$providerAccountId, label=$label, accessToken=$REDACTED, refreshToken=$REDACTED, " +
        "expiresAt=$expiresAt, disabled=$disabled)"
}

/**
 * A TeamCode workflow (a row of `workflows`).
 *
 * @property id TeamCode's id.
 * @property name Its name.
 * @property version TeamCode's version.
 * @property definition TeamCode's definition JSON.
 * @property updatedById Who saved it last (a TeamCode member id).
 * @property createdAt Epoch milliseconds.
 * @property updatedAt Epoch milliseconds.
 */
@Serializable
data class TeamCodeWorkflowDto(
    val id: String,
    val name: String,
    val version: Int,
    val definition: JsonObject,
    val updatedById: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
) {
    /** Without the definition, which holds the instructions: a log line never shows them. */
    override fun toString(): String = "TeamCodeWorkflowDto(id=$id, name=$name, version=$version)"
}

/**
 * TeamCode's `workflow_settings` row.
 *
 * @property mode `all`, `admins` or `members`.
 * @property memberIds The TeamCode members who edit, in mode `members`.
 */
@Serializable
data class TeamCodeWorkflowEditorsDto(val mode: String, val memberIds: List<String> = emptyList())

/**
 * A TeamCode automation (a row of `automations`, without its secrets).
 *
 * @property id TeamCode's id.
 * @property ownerId Its owner (a TeamCode member id).
 * @property name Its name.
 * @property triggerKind `cron` or `webhook`.
 * @property cron The schedule.
 * @property timezone The schedule's zone.
 * @property catchup `once` or `skip`.
 * @property catchupGraceMinutes How late a missed slot may still run.
 * @property actionKind `cloud`, `workflow` or `remote`.
 * @property profileId The cloud profile.
 * @property model The model.
 * @property effort The effort.
 * @property maxRuntimeMinutes The longest a session works.
 * @property prompt The prompt.
 * @property variables The webhook variables.
 * @property samplePayload The sample payload.
 * @property overlap `skip`, `queue` or `allow`.
 * @property maxRunsPerDay The most runs a day.
 * @property enabled Whether TeamCode runs it.
 * @property version TeamCode's version.
 * @property workflowId The workflow a workflow action starts.
 * @property workflowInputs Its inputs' templates.
 */
@Serializable
data class TeamCodeAutomationDto(
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
    val prompt: String? = null,
    val variables: List<JsonElement> = emptyList(),
    val samplePayload: JsonElement? = null,
    val overlap: String? = null,
    val maxRunsPerDay: Int? = null,
    val enabled: Boolean,
    val version: Int? = null,
    val workflowId: String? = null,
    val workflowInputs: Map<String, String> = emptyMap(),
) {
    /** Without the prompt, the variables and the sample payload: a log line never shows them. */
    override fun toString(): String = "TeamCodeAutomationDto(id=$id, ownerId=$ownerId, name=$name)"
}

/**
 * The repositories of a TeamCode cloud profile's current version.
 *
 * @property id TeamCode's id.
 * @property name Its name.
 * @property repositories Its repositories.
 * @property setupCommands How many setup commands its recipe has.
 */
@Serializable
data class TeamCodeProfileDto(
    val id: String,
    val name: String? = null,
    val repositories: List<String> = emptyList(),
    val setupCommands: Int = 0,
)

/** What a request's [toString] shows instead of a credential. */
private const val REDACTED = "<redacted>"

/**
 * The import request: what `ostrom import teamcode` read from TeamCode, and the member map it built.
 *
 * @property members TeamCode's members.
 * @property mcpServers TeamCode's MCP variants.
 * @property assignments The assignments.
 * @property accounts The shared accounts.
 * @property workflows TeamCode's workflows.
 * @property workflowEditors Who edits them.
 * @property automations TeamCode's automations, without their secrets.
 * @property profiles The cloud profiles the workflows and automations name.
 * @property memberMap TeamCode member id to Ostrom member id.
 */
@Serializable
data class TeamCodeImportRequest(
    val members: List<TeamCodeMemberDto> = emptyList(),
    val mcpServers: List<TeamCodeMcpServerDto> = emptyList(),
    val assignments: List<TeamCodeAssignmentDto> = emptyList(),
    val accounts: List<TeamCodeAccountDto> = emptyList(),
    val workflows: List<TeamCodeWorkflowDto> = emptyList(),
    val workflowEditors: TeamCodeWorkflowEditorsDto? = null,
    val automations: List<TeamCodeAutomationDto> = emptyList(),
    val profiles: List<TeamCodeProfileDto> = emptyList(),
    val memberMap: Map<String, String> = emptyMap(),
)

/**
 * One line of the report.
 *
 * @property kind `setting`, `secret`, `server`, `policy`, `assignment`, `account`, `workflow`, `editors` or
 *   `automation`.
 * @property name Its name.
 * @property action `planned`, `created`, `updated`, `unchanged`, `skipped` or `revive_at_cutover` (a shared account
 *   left waiting for a reconnection until the cutover run).
 * @property reason Why it was skipped, or a warning.
 * @property refreshOwnership `pending_handover` or `access_only` for a shared account left to TeamCode's refresh.
 */
@Serializable
data class ImportItemDto(
    val kind: String,
    val name: String,
    val action: String,
    val reason: String? = null,
    val refreshOwnership: String? = null,
)

/**
 * The import report.
 *
 * @property items One line per item.
 * @property created How many were created.
 * @property updated How many were updated.
 * @property unchanged How many were already as in TeamCode.
 * @property skipped How many were not imported.
 * @property reviveAtCutover How many shared accounts wait for a reconnection until the cutover run.
 * @property pendingHandover How many shared accounts wait for their handover.
 * @property accessOnly How many shared accounts serve access-only.
 */
@Serializable
data class ImportReportDto(
    val items: List<ImportItemDto>,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val skipped: Int,
    val reviveAtCutover: Int,
    val pendingHandover: Int = 0,
    val accessOnly: Int = 0,
)
