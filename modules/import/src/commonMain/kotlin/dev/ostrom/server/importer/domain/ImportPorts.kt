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
    val listPolicies: ListArgumentPoliciesUseCase,
    val createPolicy: CreateArgumentPolicyUseCase,
    val getSettings: GetMcpSettingsUseCase,
    val putSettings: PutMcpSettingsUseCase,
)

/** Imports a shared subscription account; bound by the sharing module. */
fun interface AccountImporter {
    /**
     * Imports it, or leaves an account already shared alone.
     *
     * @param caller The admin running the import.
     * @param account The account.
     *
     * @return [ImportAction.CREATED], [ImportAction.UPDATED] or [ImportAction.UNCHANGED].
     */
    suspend fun import(caller: Caller, account: TeamCodeAccount): ImportAction
}
