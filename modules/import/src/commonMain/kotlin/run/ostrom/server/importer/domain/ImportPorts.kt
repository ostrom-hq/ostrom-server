// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.domain

import run.ostrom.server.mcprelay.domain.AssignMcpServerUseCase
import run.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCase
import run.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import run.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import run.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import run.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import run.ostrom.server.mcprelay.domain.ListMcpServersUseCase
import run.ostrom.server.mcprelay.domain.PutMcpSettingsUseCase
import run.ostrom.server.mcprelay.domain.UnassignMcpServerUseCase
import run.ostrom.server.mcprelay.domain.UpdateMcpServerUseCase
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CreateSecretUseCase
import run.ostrom.server.secrets.domain.FindSecretUseCase
import run.ostrom.server.secrets.domain.RevealSecretsUseCase
import run.ostrom.server.secrets.domain.RotateSecretUseCase
import kotlin.uuid.Uuid

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
     * @param cutover Whether this is the final run, TeamCode stopped: only then are accounts waiting for a
     *   reconnection revived with TeamCode's tokens (testing them rotates them); otherwise they are reported
     *   [ImportAction.REVIVE_AT_CUTOVER] and no token is touched at the provider.
     */
    suspend fun begin(caller: Caller, dryRun: Boolean, cutover: Boolean): AccountImportRun
}

/** Records the cutover run in the audit log (the identity module's, when it is installed). */
fun interface ImportAudit {
    /**
     * Records it, before it writes anything.
     *
     * @param caller The owner running it, as a member of the org.
     * @param user The same person as a user: the event's actor.
     * @param accounts How many of TeamCode's shared accounts it imports.
     */
    suspend fun cutover(caller: Caller, user: Uuid, accounts: Int)
}

/** One run of an [AccountImporter]. */
fun interface AccountImportRun {
    /**
     * Imports the account, or says what it would do.
     *
     * @return [ImportAction.CREATED], [ImportAction.UPDATED], [ImportAction.UNCHANGED] or
     *   [ImportAction.REVIVE_AT_CUTOVER]; [ImportAction.PLANNED] instead of a write in a dry run.
     */
    suspend fun import(account: TeamCodeAccount): ImportAction
}
