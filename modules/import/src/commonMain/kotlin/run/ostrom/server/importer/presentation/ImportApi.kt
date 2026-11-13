// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.presentation

import run.ostrom.server.importer.domain.ImportTeamCodeUseCase
import run.ostrom.server.secrets.domain.CallerResolver
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

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

/** What a request's [toString] shows instead of a credential. */
private const val REDACTED = "<redacted>"

/**
 * The import request: what `ostrom import teamcode` read from TeamCode, and the member map it built.
 *
 * @property members TeamCode's members.
 * @property mcpServers TeamCode's MCP variants.
 * @property assignments The assignments.
 * @property accounts The shared accounts.
 * @property memberMap TeamCode member id to Ostrom member id.
 */
@Serializable
data class TeamCodeImportRequest(
    val members: List<TeamCodeMemberDto> = emptyList(),
    val mcpServers: List<TeamCodeMcpServerDto> = emptyList(),
    val assignments: List<TeamCodeAssignmentDto> = emptyList(),
    val accounts: List<TeamCodeAccountDto> = emptyList(),
    val memberMap: Map<String, String> = emptyMap(),
)

/**
 * One line of the report.
 *
 * @property kind `setting`, `secret`, `server`, `policy`, `assignment` or `account`.
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
