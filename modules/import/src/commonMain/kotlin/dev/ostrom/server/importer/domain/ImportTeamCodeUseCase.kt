// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.mcprelay.domain.McpServer
import dev.ostrom.server.mcprelay.domain.McpServerPayload
import dev.ostrom.server.mcprelay.domain.McpSharing
import dev.ostrom.server.mcprelay.domain.McpTransport
import dev.ostrom.server.mcprelay.domain.StdioSpec
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.MemberRole
import kotlin.uuid.Uuid

/** Imports a TeamCode team into the caller's org, once or again: a second run changes nothing. */
interface ImportTeamCodeUseCase {
    /**
     * Imports it.
     *
     * @param caller An owner or admin of the org.
     * @param export What the CLI read from TeamCode.
     * @param memberMap TeamCode member id to Ostrom member id; unmapped members' assignments are skipped.
     * @param dryRun Reports the plan without writing anything.
     *
     * @return One line per item.
     *
     * @throws dev.ostrom.server.secrets.domain.AdminRequiredException When the caller does not administer the org.
     */
    suspend operator fun invoke(
        caller: Caller,
        export: TeamCodeExport,
        memberMap: Map<String, Uuid>,
        dryRun: Boolean,
    ): ImportReport
}

/**
 * [ImportTeamCodeUseCase] through the use cases of the secrets, MCP relay and sharing modules. An item the owning
 * module refuses (an invalid name, an unsupported command) is reported as skipped with its reason; the others go on.
 *
 * @param secrets The secrets use cases.
 * @param mcp The MCP relay use cases.
 * @param accounts The sharing module's account import, or null when it is not installed.
 */
class ImportTeamCodeUseCaseImpl(secrets: SecretPorts, mcp: McpPorts, private val accounts: AccountImporter?) :
    ImportTeamCodeUseCase {
    private val secretImporter = SecretImporter(secrets)
    private val mcpImporter = McpImporter(mcp)

    override suspend fun invoke(
        caller: Caller,
        export: TeamCodeExport,
        memberMap: Map<String, Uuid>,
        dryRun: Boolean,
    ): ImportReport {
        caller.requireAdmin()
        val plan = TeamCodeImportPlanner.plan(export, memberMap)
        if (dryRun) return ImportReport(planned(plan, export))
        val items = mutableListOf(mcpImporter.prefix(caller))
        val servers = mutableMapOf<String, McpServer>()
        for (planned in plan.servers) {
            guarded(items, ImportKind.SERVER, planned.name) {
                val payload = payload(caller, planned, items)
                val (server, item) = mcpImporter.server(caller, payload)
                servers[planned.name] = server
                items += item
                items += mcpImporter.deny(caller, server, planned.deniedTools)
            }
        }
        for (assignment in plan.assignments) {
            val server = servers[assignment.server.name] ?: continue
            guarded(items, ImportKind.ASSIGNMENT, "${server.name}/${assignment.memberId}") {
                assign(caller, server, assignment, items)
            }
        }
        items += plan.skipped
        for (account in export.accounts) importAccount(caller, account, items)
        return ImportReport(items)
    }

    private suspend fun payload(
        caller: Caller,
        planned: PlannedServer,
        items: MutableList<ImportItem>,
    ): McpServerPayload {
        val source = planned.source
        val credential = planned.sharedCredential?.let {
            val (id, item) = secretImporter.upsert(caller, planned.secretName, it, personal = false)
            items += item
            id
        }
        val stdio = source.stdio?.let { command ->
            val secretEnv = command.secretEnv.mapValues { (variable, value) ->
                val (id, item) = secretImporter.upsert(caller, planned.variableSecretName(variable), value, false)
                items += item
                id
            }
            StdioSpec(command.launcher, command.packageName, command.args, secretEnv = secretEnv)
        }
        return McpServerPayload(
            name = planned.name,
            transport = if (stdio == null) McpTransport.HTTP else McpTransport.STDIO,
            url = source.url.takeIf { stdio == null },
            credentialSecretId = credential,
            credentialHeader = source.credentialHeader.takeIf { stdio == null },
            stdio = stdio,
            sharing = if (source.perMember) McpSharing.PER_MEMBER else McpSharing.SHARED,
        )
    }

    private suspend fun assign(
        caller: Caller,
        server: McpServer,
        assignment: PlannedAssignment,
        items: MutableList<ImportItem>,
    ) {
        // A member's own credential is their personal secret, written as them.
        val member = Caller(caller.orgId, assignment.memberId, MemberRole.MEMBER)
        val name = assignment.server.secretName
        val carried = server.sharing == McpSharing.PER_MEMBER && server.transport == McpTransport.HTTP
        var credential: Uuid? = null
        val memberSecret = assignment.memberSecret
        if (memberSecret != null && carried) {
            val (id, item) = secretImporter.upsert(member, name, memberSecret, personal = true)
            credential = id
            items += item
        } else if (memberSecret != null) {
            items +=
                ImportItem(ImportKind.SECRET, "$name (personal, ${member.memberId})", ImportAction.SKIPPED, NOT_CARRIED)
        }
        items += mcpImporter.assign(caller, server, assignment.memberId, assignment.server.allowedTools, credential)
    }

    private suspend fun importAccount(caller: Caller, account: TeamCodeAccount, items: MutableList<ImportItem>) {
        val name = "${account.provider}/${account.label}"
        val importer = accounts
        if (importer == null) {
            items += ImportItem(ImportKind.ACCOUNT, name, ImportAction.SKIPPED, NO_SHARING)
            return
        }
        guarded(items, ImportKind.ACCOUNT, name) {
            items += ImportItem(ImportKind.ACCOUNT, name, importer.import(caller, account))
        }
    }

    /** Runs [block]; a refusal of the owning module is reported as skipped and the import goes on. */
    private suspend fun guarded(
        items: MutableList<ImportItem>,
        kind: ImportKind,
        name: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (refused: OstromException) {
            // Refusals only: a cancellation or an unforeseen error still stops the import.
            items += ImportItem(kind, name, ImportAction.SKIPPED, refused.message)
        }
    }

    private fun planned(plan: TeamCodeImportPlan, export: TeamCodeExport): List<ImportItem> {
        val items = mutableListOf(ImportItem(ImportKind.SETTING, "agentPrefix", ImportAction.PLANNED))
        for (server in plan.servers) {
            if (server.sharedCredential != null) {
                items += ImportItem(ImportKind.SECRET, server.secretName, ImportAction.PLANNED)
            }
            server.source.stdio?.let { command ->
                items += command.secretEnv.keys.map {
                    ImportItem(ImportKind.SECRET, server.variableSecretName(it), ImportAction.PLANNED)
                }
            }
            items += ImportItem(ImportKind.SERVER, server.name, ImportAction.PLANNED)
            items +=
                server.deniedTools.map { ImportItem(ImportKind.POLICY, "${server.name}/$it", ImportAction.PLANNED) }
        }
        items += plan.assignments.map {
            ImportItem(ImportKind.ASSIGNMENT, "${it.server.name}/${it.memberId}", ImportAction.PLANNED)
        }
        // In the order the real import reports them: what the plan skips comes before the accounts.
        items += plan.skipped
        items += export.accounts.map {
            val action = if (accounts == null) ImportAction.SKIPPED else ImportAction.PLANNED
            ImportItem(ImportKind.ACCOUNT, "${it.provider}/${it.label}", action, NO_SHARING.takeIf { accounts == null })
        }
        return items
    }

    private companion object {
        const val NOT_CARRIED = "Only an HTTP per-member server carries a member's credential: set it again in Ostrom."
        const val NO_SHARING = "This server does not import shared accounts: share them again from the console."
    }
}
