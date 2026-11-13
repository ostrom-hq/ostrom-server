// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.domain

import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.secrets.domain.SecretValue
import kotlin.time.Instant

/**
 * A TeamCode team as `ostrom import teamcode` read it from TeamCode, without writing to it: secrets already decrypted
 * with TeamCode's key by the CLI, sent once over TLS and sealed here under the instance key.
 *
 * @property members TeamCode's members, matched to Ostrom members by the request's member map.
 * @property servers TeamCode's MCP variants.
 * @property assignments Which member uses which variant, with their own credential.
 * @property accounts The team's shared subscription accounts.
 * @property processes The team's workflows, their editing rule and its automations.
 */
data class TeamCodeExport(
    val members: List<TeamCodeMember> = emptyList(),
    val servers: List<TeamCodeMcpServer> = emptyList(),
    val assignments: List<TeamCodeAssignment> = emptyList(),
    val accounts: List<TeamCodeAccount> = emptyList(),
    val processes: TeamCodeProcesses = TeamCodeProcesses(),
)

/**
 * A TeamCode member.
 *
 * @property id TeamCode's id.
 * @property username TeamCode's username, for the report.
 */
data class TeamCodeMember(val id: String, val username: String)

/** How a TeamCode tool policy reads its list. */
enum class ToolMode {
    /** Only the listed tools. */
    ALLOW,

    /** Every tool but the listed ones. */
    DENY,
}

/**
 * An MCP variant of TeamCode: a slot (`target`, the name agents see) served by one of its variants.
 *
 * @property id TeamCode's variant id.
 * @property target The slot, such as `grafana` (tools `mcp__teamcode-grafana__*`).
 * @property variant The variant's name within the slot.
 * @property perMember Whether each member brings their own credential.
 * @property url The HTTPS endpoint, for an HTTP server.
 * @property credentialHeader The header carrying the credential (`Authorization: Bearer` when null).
 * @property sharedSecret The variant's shared credential.
 * @property stdio The command, for a server run by the MCP host.
 * @property toolMode How [tools] reads, or null for every tool.
 * @property tools The tool policy's list.
 */
data class TeamCodeMcpServer(
    val id: String,
    val target: String,
    val variant: String,
    val perMember: Boolean = false,
    val url: String? = null,
    val credentialHeader: String? = null,
    val sharedSecret: SecretValue? = null,
    val stdio: TeamCodeStdio? = null,
    val toolMode: ToolMode? = null,
    val tools: List<String> = emptyList(),
)

/**
 * A command run by the MCP host.
 *
 * @property launcher The registry.
 * @property packageName The pinned package.
 * @property args Its arguments.
 * @property secretEnv Its secret variables, by name.
 */
data class TeamCodeStdio(
    val launcher: StdioLauncher,
    val packageName: String,
    val args: List<String> = emptyList(),
    val secretEnv: Map<String, SecretValue> = emptyMap(),
)

/**
 * A member's assignment to a variant.
 *
 * @property memberId TeamCode's member id.
 * @property serverId TeamCode's variant id.
 * @property memberSecret The member's own credential, for a per-member variant.
 */
data class TeamCodeAssignment(val memberId: String, val serverId: String, val memberSecret: SecretValue? = null)

/**
 * A shared subscription account of TeamCode.
 *
 * @property provider TeamCode's provider (`claude`, `codex`).
 * @property providerAccountId The provider's account id.
 * @property label Its display name.
 * @property email Its e-mail, when known.
 * @property accessToken The OAuth access token.
 * @property refreshToken The OAuth refresh token.
 * @property expiresAt When [accessToken] expires.
 * @property disabled Whether TeamCode had it disabled.
 */
data class TeamCodeAccount(
    val provider: String,
    val providerAccountId: String,
    val label: String,
    val email: String?,
    val accessToken: SecretValue,
    val refreshToken: SecretValue,
    val expiresAt: Instant,
    val disabled: Boolean = false,
)
