// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.mcprelay.domain.AssignMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCase
import dev.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpServersUseCase
import dev.ostrom.server.mcprelay.domain.PutMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.UnassignMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.UpdateMcpServerUseCase
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CreateSecretUseCase
import dev.ostrom.server.secrets.domain.FindSecretUseCase
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.RotateSecretUseCase

/**
 * The secrets use cases the import writes through: it owns no table, the secrets module checks and seals.
 *
 * @property find Finds a secret by name.
 * @property create Creates one.
 * @property rotate Replaces its value.
 * @property reveal Reads a value, to leave an unchanged secret alone.
 */
data class SecretPorts(
    val find: FindSecretUseCase,
    val create: CreateSecretUseCase,
    val rotate: RotateSecretUseCase,
    val reveal: RevealSecretsUseCase,
)

/**
 * The MCP relay use cases the import writes through.
 *
 * @property listServers The org's servers.
 * @property createServer Declares a server.
 * @property updateServer Changes one.
 * @property listAssignments A server's assignments.
 * @property assign Assigns a server to a member.
 * @property unassign Removes an assignment TeamCode no longer has.
 * @property listPolicies A server's argument policies.
 * @property createPolicy Adds one.
 * @property getSettings The agent prefix.
 * @property putSettings Changes it.
 */
data class McpPorts(
    val listServers: ListMcpServersUseCase,
    val createServer: CreateMcpServerUseCase,
    val updateServer: UpdateMcpServerUseCase,
    val listAssignments: ListMcpAssignmentsUseCase,
    val assign: AssignMcpServerUseCase,
    val unassign: UnassignMcpServerUseCase,
    val listPolicies: ListArgumentPoliciesUseCase,
    val createPolicy: CreateArgumentPolicyUseCase,
    val getSettings: GetMcpSettingsUseCase,
    val putSettings: PutMcpSettingsUseCase,
)

/** Imports shared subscription accounts ([SharedAccountImporter] when the sharing module is installed). */
fun interface AccountImporter {
    /**
     * Starts one run: reads the org's accounts once, then imports TeamCode's one by one.
     *
     * @param caller The admin running the import.
     * @param dryRun Whether to write nothing: the run then makes the same reads and refuses the same accounts, the
     *   earlier accounts of the same run counted as if written.
     */
    suspend fun begin(caller: Caller, dryRun: Boolean): AccountImportRun
}

/** One run of an [AccountImporter]. */
fun interface AccountImportRun {
    /**
     * Imports the account, or says what it would do.
     *
     * @return [ImportAction.CREATED], [ImportAction.UPDATED] or [ImportAction.UNCHANGED]; [ImportAction.PLANNED]
     *   instead of a write in a dry run.
     */
    suspend fun import(account: TeamCodeAccount): ImportAction
}
