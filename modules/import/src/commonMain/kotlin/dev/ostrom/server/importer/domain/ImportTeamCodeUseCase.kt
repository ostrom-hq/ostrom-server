// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/** Imports a TeamCode team into the caller's org, once or again: a second run changes nothing. */
interface ImportTeamCodeUseCase {
    /**
     * Imports it.
     *
     * @param caller An owner or admin of the org.
     * @param export What the CLI read from TeamCode.
     * @param memberMap TeamCode member id to Ostrom member id; unmapped members' assignments are skipped. The ids are
     *   not checked against the org's members: this server has no members port yet.
     * @param dryRun Reports what the import would do without writing anything.
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
 * The import keeps its own record of what it created ([ImportProvenanceRepository]): it updates only that, and reports
 * a server or secret of the same name an admin created by hand as skipped. A dry run reads the same state and reports
 * the same lines: unchanged and skipped where the reads already tell (an identical server, secret, denial or
 * assignment; a name collision; a server or secret it did not create; a prefix it keeps), planned for what it would
 * write. A write the owning module could still refuse (a stdio server without an MCP host, an invalid name or value)
 * stays planned: the dry run never runs the owning module's checks.
 *
 * @param secrets The secrets use cases.
 * @param mcp The MCP relay use cases.
 * @param provenance What the import created, per org.
 * @param accounts The sharing module's account import, or null when it is not installed.
 */
class ImportTeamCodeUseCaseImpl(
    private val secrets: SecretPorts,
    private val mcp: McpPorts,
    private val provenance: ImportProvenanceRepository,
    private val accounts: AccountImporter?,
) : ImportTeamCodeUseCase {
    override suspend fun invoke(
        caller: Caller,
        export: TeamCodeExport,
        memberMap: Map<String, Uuid>,
        dryRun: Boolean,
    ): ImportReport {
        caller.requireAdmin()
        val records = provenance.list(caller.orgId)
        val serverRecords = records.filter { it.kind == ImportRecordKind.SERVER }
        val recorded = serverRecords.associate { it.sourceKey to it.ostromName }
        val servers = serverRecords.associate { it.ostromId to it.sourceKey }
        val plan = TeamCodeImportPlanner.plan(export, memberMap, recorded)
        val record: Recorder = { provenance.record(caller.orgId, it) }
        val run = ImportRun(
            caller,
            SecretImporter(secrets, dryRun, records.ids(ImportRecordKind.SECRET), record),
            McpImporter(mcp, dryRun, mcp.listServers(caller), servers, record),
        )
        val items = run.import(plan)
        for (account in export.accounts) importAccount(caller, account, dryRun, items)
        return ImportReport(items)
    }

    private suspend fun importAccount(
        caller: Caller,
        account: TeamCodeAccount,
        dryRun: Boolean,
        items: MutableList<ImportItem>,
    ) {
        val name = "${account.provider}/${account.label}"
        val importer = accounts
        when {
            importer == null -> items += ImportItem(ImportKind.ACCOUNT, name, ImportAction.SKIPPED, NO_SHARING)
            dryRun -> items += ImportItem(ImportKind.ACCOUNT, name, ImportAction.PLANNED)
            else -> guarded(items, ImportKind.ACCOUNT, name) {
                items += ImportItem(ImportKind.ACCOUNT, name, importer.import(caller, account))
            }
        }
    }

    private fun List<ImportRecord>.ids(kind: ImportRecordKind): Set<Uuid> =
        filter { it.kind == kind }.map { it.ostromId }.toSet()

    private companion object {
        const val NO_SHARING = "This server does not import shared accounts: share them again from the console."
    }
}
