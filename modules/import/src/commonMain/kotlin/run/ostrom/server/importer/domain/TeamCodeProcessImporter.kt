// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.domain

import run.ostrom.server.automations.domain.AutomationImport
import run.ostrom.server.automations.domain.ImportAutomationsUseCase
import run.ostrom.server.compiler.domain.usecases.ImportWorkflowsUseCase
import run.ostrom.server.compiler.domain.usecases.WorkflowImport
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/** Sets who edits the organization's workflows (`PUT /api/v1/workflows/settings`), bound by the module serving it. */
fun interface WorkflowEditorsImporter {
    /**
     * Sets them, or says what it would do.
     *
     * @param caller The admin running the import.
     * @param mode `all`, `admins` or `members`.
     * @param memberIds The Ostrom members who edit, in mode `members`.
     * @param dryRun Writes nothing.
     * @return [ImportAction.UPDATED] or [ImportAction.UNCHANGED]; [ImportAction.PLANNED] for a change in a dry run.
     */
    suspend fun import(caller: Caller, mode: String, memberIds: List<Uuid>, dryRun: Boolean): ImportAction
}

/**
 * What a run imports besides servers, secrets and accounts.
 *
 * @property workflows TeamCode's workflows.
 * @property editors Who edits them, or null when the export has no setting.
 * @property automations TeamCode's automations.
 * @property profiles The profiles they name.
 */
data class TeamCodeProcesses(
    val workflows: List<TeamCodeWorkflow> = emptyList(),
    val editors: TeamCodeWorkflowEditors? = null,
    val automations: List<TeamCodeAutomation> = emptyList(),
    val profiles: List<TeamCodeProfile> = emptyList(),
)

/**
 * Imports TeamCode's workflows, its editing rule and its automations, through the compiler's and the automations'
 * use cases (docs/decisions/workflows-one-system.md, section 5). Each line of the report names the workflow
 * (`tc-<id>`) or the automation (`<owner>/<name>`), never its prompt, instruction or payload. A workflow is imported
 * after the workflows it calls; an automation after every workflow.
 *
 * @param workflows The compiler's import, or null when this server keeps no workflows.
 * @param automations The automations' import, or null when this server runs none.
 * @param editors The editing rights, or null until a module serves them.
 * @param logins The members' logins (approvers, authors and the importer), or null without a member directory.
 */
class TeamCodeProcessImporter(
    private val workflows: ImportWorkflowsUseCase?,
    private val automations: ImportAutomationsUseCase?,
    private val editors: WorkflowEditorsImporter?,
    private val logins: MemberLogins?,
) {
    /**
     * Imports them, appending one line per item to [items].
     *
     * @param caller The admin running the import.
     * @param processes What TeamCode has.
     * @param members TeamCode's members, for the names of the report.
     * @param memberMap TeamCode member id to Ostrom member id.
     * @param mode How the run goes.
     * @param items The report.
     */
    @Suppress("LongParameterList") // The run's caller, what it imports, how it maps people, and its report.
    suspend fun import(
        caller: Caller,
        processes: TeamCodeProcesses,
        members: List<TeamCodeMember>,
        memberMap: Map<String, Uuid>,
        mode: ProcessRunMode,
        items: MutableList<ImportItem>,
    ) {
        val loginsById = logins?.loginsOf(caller.orgId, (memberMap.values + caller.memberId).distinct()).orEmpty()
        val mapping = TeamCodeMapping(
            memberMap.mapNotNull { (teamCode, ostrom) -> loginsById[ostrom]?.let { teamCode to it } }.toMap(),
            processes.profiles.associateBy { it.id },
        )
        val by = loginsById[caller.memberId] ?: caller.memberId.toString()
        for (mapped in ordered(processes.workflows.map { TeamCodeWorkflowMapper.map(it, mapping) })) {
            importWorkflow(caller, mapped, by, mode.dryRun, items)
        }
        processes.editors?.let { importEditors(caller, it, memberMap, mode.dryRun, items) }
        val usernames = members.associate { it.id to it.username }
        for (automation in processes.automations) {
            val name = "${usernames[automation.ownerId] ?: automation.ownerId}/${automation.name}"
            val mapped = TeamCodeAutomationMapper.map(
                automation,
                memberMap[automation.ownerId],
                mapping.profiles,
                mode.cutover,
            )
            importAutomation(caller, name, mapped, mode.dryRun, items)
        }
    }

    private suspend fun importWorkflow(
        caller: Caller,
        mapped: MappedWorkflow,
        by: String,
        dryRun: Boolean,
        items: MutableList<ImportItem>,
    ) {
        val raw = mapped.raw
        val use = workflows
        if (raw == null || use == null) {
            val reason = if (raw == null) mapped.notes.joinToString(" ") else NO_WORKFLOWS
            items += ImportItem(ImportKind.WORKFLOW, mapped.id, ImportAction.SKIPPED, reason)
            return
        }
        guarded(items, ImportKind.WORKFLOW, mapped.id) {
            val action = when (use.import(caller.orgId, raw, by, dryRun)) {
                WorkflowImport.CREATED -> if (dryRun) ImportAction.PLANNED else ImportAction.CREATED
                WorkflowImport.UPDATED -> if (dryRun) ImportAction.PLANNED else ImportAction.UPDATED
                WorkflowImport.UNCHANGED -> ImportAction.UNCHANGED
            }
            items += ImportItem(ImportKind.WORKFLOW, mapped.id, action, notes(mapped.notes))
        }
    }

    private suspend fun importEditors(
        caller: Caller,
        setting: TeamCodeWorkflowEditors,
        memberMap: Map<String, Uuid>,
        dryRun: Boolean,
        items: MutableList<ImportItem>,
    ) {
        val unmapped = setting.memberIds.count { it !in memberMap }
        val note = if (unmapped == 0) null else "$unmapped of its members have no Ostrom member and are left out."
        val use = editors
        if (use == null) {
            items += ImportItem(ImportKind.EDITORS, setting.mode, ImportAction.SKIPPED, NO_EDITORS)
            return
        }
        guarded(items, ImportKind.EDITORS, setting.mode) {
            val action = use.import(caller, setting.mode, setting.memberIds.mapNotNull(memberMap::get), dryRun)
            items += ImportItem(ImportKind.EDITORS, setting.mode, action, note)
        }
    }

    private suspend fun importAutomation(
        caller: Caller,
        name: String,
        mapped: MappedAutomation,
        dryRun: Boolean,
        items: MutableList<ImportItem>,
    ) {
        val automation = mapped.automation
        val use = automations
        if (automation == null || use == null) {
            val reason = if (automation == null) mapped.notes.joinToString(" ") else NO_AUTOMATIONS
            items += ImportItem(ImportKind.AUTOMATION, name, ImportAction.SKIPPED, reason)
            return
        }
        guarded(items, ImportKind.AUTOMATION, name) {
            val action = when (use.import(caller.orgId, automation, dryRun)) {
                AutomationImport.CREATED -> if (dryRun) ImportAction.PLANNED else ImportAction.CREATED
                AutomationImport.UPDATED -> if (dryRun) ImportAction.PLANNED else ImportAction.UPDATED
                AutomationImport.UNCHANGED -> ImportAction.UNCHANGED
            }
            items += ImportItem(ImportKind.AUTOMATION, name, action, notes(mapped.notes))
        }
    }

    private fun notes(notes: List<String>): String? = notes.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/**
 * How a run goes.
 *
 * @property dryRun Writes nothing.
 * @property cutover The cutover run: automations are armed as TeamCode had them.
 */
data class ProcessRunMode(val dryRun: Boolean, val cutover: Boolean)

/**
 * The workflows in an order that imports a called workflow before its callers (then by id); a call loop, which the
 * save refuses, keeps its id order.
 */
internal fun ordered(workflows: List<MappedWorkflow>): List<MappedWorkflow> {
    val byId = workflows.associateBy { it.id }
    val done = linkedMapOf<String, MappedWorkflow>()
    val visiting = mutableSetOf<String>()
    fun visit(workflow: MappedWorkflow) {
        if (workflow.id in done || !visiting.add(workflow.id)) return
        workflow.calls.sorted().mapNotNull(byId::get).forEach(::visit)
        done[workflow.id] = workflow
    }
    workflows.sortedBy { it.id }.forEach(::visit)
    return done.values.toList()
}

private const val NO_WORKFLOWS = "This server keeps no workflows."
private const val NO_AUTOMATIONS = "This server runs no automations."
private const val NO_EDITORS = "This server does not set who edits the workflows yet (workflows.settings.v1): set it " +
    "in the console once it does."
