// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.MemberRole
import kotlin.uuid.Uuid

/** Imports a TeamCode team into the caller's org, once or again: a second run changes nothing. */
interface ImportTeamCodeUseCase {
    /**
     * Imports it.
     *
     * @param caller An owner or admin of the org.
     * @param export What the CLI read from TeamCode.
     * @param memberMap TeamCode member id to Ostrom member id; unmapped members' assignments are skipped. Every Ostrom
     *   id must be an active member of the caller's org, or nothing is imported.
     * @param dryRun Reports what the import would do without writing anything.
     * @param cutoverBy For the final run, once TeamCode is stopped, the user who runs it (null for a normal run): an
     *   owner only, recorded in the audit log in that user's name; it also revives the shared accounts waiting for a
     *   reconnection with TeamCode's tokens and hands over those waiting for their handover ([AccountImporter.begin]).
     * @param accessOnly TeamCode's `--access-only` trial for the shared accounts ([AccountImporter.begin]); never with
     *   [cutoverBy].
     *
     * @return One line per item.
     *
     * @throws run.ostrom.server.secrets.domain.AdminRequiredException When the caller does not administer the org.
     * @throws UnknownImportMembersException When an Ostrom id of [memberMap] is not a member of the caller's org.
     * @throws CutoverOwnerRequiredException When [cutoverBy] is given and the caller is an admin, not an owner.
     * @throws CutoverAuditUnavailableException When [cutoverBy] is given and no audit log is installed.
     * @throws CutoverSharingUnavailableException When [cutoverBy] is given and this server does not import shared
     *   accounts.
     * @throws AccessOnlyCutoverException When both [accessOnly] and [cutoverBy] are given.
     */
    @Suppress("LongParameterList") // TeamCode's import: what it reads, how it maps it, and the three ways it runs.
    suspend operator fun invoke(
        caller: Caller,
        export: TeamCodeExport,
        memberMap: Map<String, Uuid>,
        dryRun: Boolean,
        cutoverBy: Uuid?,
        accessOnly: Boolean,
    ): ImportReport
}

/** The cutover hands the accounts over to Ostrom: it cannot leave them access-only too. */
class AccessOnlyCutoverException :
    OstromException(
        ErrorKind.INVALID_REQUEST,
        "Choose either the cutover, which hands the accounts over to Ostrom, or the access-only trial.",
    )

/** The member map names Ostrom ids that are not members of the caller's org. */
class UnknownImportMembersException(teamCodeIds: List<String>) :
    OstromException(
        ErrorKind.INVALID_REQUEST,
        "These TeamCode members are mapped to ids that are not members of this organization: " +
            "${teamCodeIds.joinToString()}. Map them to members of the organization, or leave them unmapped. " +
            "Nothing was imported.",
    )

/** Only an owner runs the cutover import: a 403 `cutover_owner_required`, so the console names the reason. */
class CutoverOwnerRequiredException :
    OstromException(
        ErrorKind.FORBIDDEN,
        "Only an owner of the organization runs the cutover import.",
        "cutover_owner_required",
    )

/** The cutover import is recorded in the audit log, and this server has none: a 503 `cutover_audit_unavailable`. */
class CutoverAuditUnavailableException :
    OstromException(
        ErrorKind.UNAVAILABLE,
        "This server keeps no audit log, so it cannot record a cutover import.",
        "cutover_audit_unavailable",
    )

/**
 * The cutover run revives shared accounts, and this server does not import them: a 503
 * `cutover_sharing_unavailable`.
 */
class CutoverSharingUnavailableException :
    OstromException(
        ErrorKind.UNAVAILABLE,
        "This server does not import shared accounts, so it has no cutover to run.",
        "cutover_sharing_unavailable",
    )

/** This server does not import shared accounts. */
private class NoSharingException :
    OstromException(
        ErrorKind.UNAVAILABLE,
        "This server does not import shared accounts: share them again from the console.",
    )

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
 * @param members Who is an active member of an org: a member map naming anyone else is refused before any write.
 * @param accounts The sharing module's account import, or null when it is not installed.
 * @param audit The audit log of a cutover run, or null when none is installed (a cutover is then refused, as it is
 *   without [accounts]).
 * @param processes Imports the workflows, their editing rule and the automations, after everything else.
 */
@Suppress("LongParameterList") // One port per module the import writes through.
class ImportTeamCodeUseCaseImpl(
    private val secrets: SecretPorts,
    private val mcp: McpPorts,
    private val provenance: ImportProvenanceRepository,
    private val members: MemberDirectory,
    private val accounts: AccountImporter?,
    private val audit: ImportAudit?,
    private val processes: TeamCodeProcessImporter = TeamCodeProcessImporter(null, null, null, null),
) : ImportTeamCodeUseCase {
    override suspend fun invoke(
        caller: Caller,
        export: TeamCodeExport,
        memberMap: Map<String, Uuid>,
        dryRun: Boolean,
        cutoverBy: Uuid?,
        accessOnly: Boolean,
    ): ImportReport {
        caller.requireAdmin()
        if (accessOnly && cutoverBy != null) throw AccessOnlyCutoverException()
        // One lookup per Ostrom id, however many TeamCode members map to it.
        val outside = memberMap.values.toSet().filterNot { members.isActiveMember(caller.orgId, it.toString()) }
        val strangers = memberMap.filterValues { it in outside }.keys
        if (strangers.isNotEmpty()) throw UnknownImportMembersException(strangers.sorted())
        cutoverBy?.let { startCutover(caller, it, export.accounts.size, dryRun) }
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
        importAccounts(caller, export.accounts, AccountRunMode(dryRun, cutoverBy != null, accessOnly), items)
        val mode = ProcessRunMode(dryRun, cutover = cutoverBy != null)
        processes.import(caller, export.processes, export.members, memberMap, mode, items)
        return ImportReport(items)
    }

    /**
     * Refuses a cutover but by an owner, on a server that imports shared accounts and keeps an audit log, and records
     * it in the name of [user] unless it is a dry run.
     */
    private suspend fun startCutover(caller: Caller, user: Uuid, accounts: Int, dryRun: Boolean) {
        val refusal = when {
            caller.role != MemberRole.OWNER -> CutoverOwnerRequiredException()
            this.accounts == null -> CutoverSharingUnavailableException()
            else -> null
        }
        refusal?.let { throw it }
        val log = audit ?: throw CutoverAuditUnavailableException()
        if (!dryRun) log.cutover(caller, user, accounts)
    }

    /**
     * One account run for all of TeamCode's accounts, so it reads the org's accounts once. When it cannot begin (no
     * sharing module, the org's accounts unreadable), each account is skipped with the reason, and what the import
     * already wrote stays reported.
     */
    private suspend fun importAccounts(
        caller: Caller,
        accounts: List<TeamCodeAccount>,
        mode: AccountRunMode,
        items: MutableList<ImportItem>,
    ) {
        if (accounts.isEmpty()) return
        val run = try {
            (this.accounts ?: throw NoSharingException()).begin(caller, mode.dryRun, mode.cutover, mode.accessOnly)
        } catch (refused: OstromException) {
            // A refusal only, as for an item: a cancellation or an unforeseen error still stops the import.
            AccountImportRun { throw refused }
        }
        for (account in accounts) {
            val name = "${account.provider.trim().lowercase()}/${account.label}"
            guarded(items, ImportKind.ACCOUNT, name) {
                val outcome = run.import(account)
                val reason = when {
                    outcome.action == ImportAction.REVIVE_AT_CUTOVER -> WAITS_FOR_CUTOVER
                    outcome.handover == ImportHandover.PENDING_HANDOVER -> WAITS_FOR_HANDOVER
                    outcome.handover == ImportHandover.ACCESS_ONLY -> SERVES_ACCESS_ONLY
                    else -> null
                }
                items += ImportItem(ImportKind.ACCOUNT, name, outcome.action, reason, outcome.handover)
            }
        }
    }

    private fun List<ImportRecord>.ids(kind: ImportRecordKind): Set<Uuid> =
        filter { it.kind == kind }.map { it.ostromId }.toSet()

    /** How a run treats the shared accounts. */
    private class AccountRunMode(val dryRun: Boolean, val cutover: Boolean, val accessOnly: Boolean)

    private companion object {
        const val WAITS_FOR_HANDOVER = "TeamCode may still renew this account's tokens: it serves nothing in Ostrom " +
            "until it is handed over (the cutover run, or a handover once TeamCode no longer uses it)."
        const val SERVES_ACCESS_ONLY = "Serves with TeamCode's access token, never renewed by Ostrom: import again " +
            "when it expires."
        const val WAITS_FOR_CUTOVER = "This account waits for a reconnection. It is left so while TeamCode runs " +
            "(testing TeamCode's tokens would disconnect TeamCode): the cutover run revives it."
    }
}
