// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
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
     * @param memberMap TeamCode member id to Ostrom member id; unmapped members' assignments are skipped. The ids are
     *   not checked against the org's members: this server has no members port yet.
     * @param dryRun Reports what the import would do without writing anything.
     * @param cutover The final run, once TeamCode is stopped: an owner only, recorded in the audit log; it also revives
     *   the shared accounts waiting for a reconnection with TeamCode's tokens ([AccountImporter.begin]).
     *
     * @return One line per item.
     *
     * @throws dev.ostrom.server.secrets.domain.AdminRequiredException When the caller does not administer the org.
     * @throws CutoverOwnerRequiredException When [cutover] is asked by an admin who is not an owner.
     * @throws CutoverAuditUnavailableException When [cutover] is asked and no audit log is installed.
     */
    suspend operator fun invoke(
        caller: Caller,
        export: TeamCodeExport,
        memberMap: Map<String, Uuid>,
        dryRun: Boolean,
        cutover: Boolean = false,
    ): ImportReport
}

/** Only an owner runs the cutover import. */
class CutoverOwnerRequiredException :
    OstromException(ErrorKind.FORBIDDEN, "Only an owner of the organization runs the cutover import.")

/** The cutover import is recorded in the audit log, and this server has none. */
class CutoverAuditUnavailableException :
    OstromException(ErrorKind.UNAVAILABLE, "This server keeps no audit log, so it cannot record a cutover import.")

/**
 * [ImportTeamCodeUseCase] through the use cases of the secrets, MCP relay and sharing modules. An item the owning
 * module refuses (an invalid name, an unsupported command) is reported as skipped with its reason; the others go on.
 *
 * The import keeps its own record of what it created ([ImportProvenanceRepository]): it updates only that, and reports
 * a server or secret of the same name an admin created by hand as skipped. A dry run reads the same state and reports
 * the same lines: unchanged and skipped where the reads already tell (an identical server, secret, denial or
 * assignment; a name collision; a server or secret it did not create; a prefix it keeps), planned for what it would
 * write. A write the owning module could still refuse (a stdio server without an MCP host, an invalid name or value)
 * stays planned: the dry run never runs the owning module's checks. Shared accounts are the exception: their dry run
 * runs the sharing module's checks and counts the run's earlier accounts ([AccountImporter]).
 *
 * @param secrets The secrets use cases.
 * @param mcp The MCP relay use cases.
 * @param provenance What the import created, per org.
 * @param accounts The sharing module's account import, or null when it is not installed.
 * @param audit The audit log of a cutover run, or null when none is installed (a cutover is then refused).
 */
class ImportTeamCodeUseCaseImpl(
    private val secrets: SecretPorts,
    private val mcp: McpPorts,
    private val provenance: ImportProvenanceRepository,
    private val accounts: AccountImporter?,
    private val audit: ImportAudit?,
) : ImportTeamCodeUseCase {
    override suspend fun invoke(
        caller: Caller,
        export: TeamCodeExport,
        memberMap: Map<String, Uuid>,
        dryRun: Boolean,
        cutover: Boolean,
    ): ImportReport {
        caller.requireAdmin()
        if (cutover) startCutover(caller, export.accounts.size, dryRun)
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
        importAccounts(caller, export.accounts, dryRun, cutover, items)
        return ImportReport(items)
    }

    /** Refuses a cutover but by an owner with an audit log, and records it unless it is a dry run. */
    private suspend fun startCutover(caller: Caller, accounts: Int, dryRun: Boolean) {
        if (caller.role != MemberRole.OWNER) throw CutoverOwnerRequiredException()
        val log = audit ?: throw CutoverAuditUnavailableException()
        if (!dryRun) log.cutover(caller, accounts)
    }

    /** One account run for all of TeamCode's accounts, so it reads the org's accounts once. */
    private suspend fun importAccounts(
        caller: Caller,
        accounts: List<TeamCodeAccount>,
        dryRun: Boolean,
        cutover: Boolean,
        items: MutableList<ImportItem>,
    ) {
        if (accounts.isEmpty()) return
        val run = this.accounts?.begin(caller, dryRun, cutover)
        for (account in accounts) {
            val name = "${account.provider.trim().lowercase()}/${account.label}"
            when (run) {
                null -> items += ImportItem(ImportKind.ACCOUNT, name, ImportAction.SKIPPED, NO_SHARING)
                else -> guarded(items, ImportKind.ACCOUNT, name) {
                    val action = run.import(account)
                    val reason = WAITS_FOR_CUTOVER.takeIf { action == ImportAction.REVIVE_AT_CUTOVER }
                    items += ImportItem(ImportKind.ACCOUNT, name, action, reason)
                }
            }
        }
    }

    private fun List<ImportRecord>.ids(kind: ImportRecordKind): Set<Uuid> =
        filter { it.kind == kind }.map { it.ostromId }.toSet()

    private companion object {
        const val NO_SHARING = "This server does not import shared accounts: share them again from the console."
        const val WAITS_FOR_CUTOVER = "This account waits for a reconnection. It is left so while TeamCode runs " +
            "(testing TeamCode's tokens would disconnect TeamCode): the cutover run revives it."
    }
}
