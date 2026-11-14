// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.domain

import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.server.compiler.domain.usecases.WorkflowSettingsUseCase
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.MemberRole

/**
 * [WorkflowEditorsImporter] on the compiler's editing rights (`workflows.settings.v1`): the rule is saved, checked and
 * audited (`workflow.editors.changed`) as `PUT /api/v1/workflows/settings` saves it, and left alone when it is already
 * TeamCode's.
 *
 * @param settings The editing rights.
 */
class SettingsWorkflowEditors(private val settings: WorkflowSettingsUseCase) : WorkflowEditorsImporter {
    override suspend fun import(
        caller: Caller,
        by: String,
        principal: AuthenticatedPrincipal,
        change: EditorsChange,
        dryRun: Boolean,
    ): ImportAction {
        val role = if (caller.role == MemberRole.OWNER) OrgRole.OWNER else OrgRole.ADMIN
        val current = settings.settings(caller.orgId, by, role).editors
        val ids = change.memberIds.map { it.toString() }.distinct()
        val same = current.mode == change.mode && current.memberIds.orEmpty().toSet() == ids.toSet()
        return when {
            same -> ImportAction.UNCHANGED
            dryRun -> ImportAction.PLANNED
            else -> {
                val editors = WorkflowEditors(change.mode, ids.takeIf { it.isNotEmpty() })
                settings.save(caller.orgId, by, role, principal, editors)
                ImportAction.UPDATED
            }
        }
    }
}

/**
 * The editing rights the import sets: the compiler's, when it is installed.
 *
 * @param settings The compiler's editing rights, or null.
 * @return The importer, or null.
 */
fun workflowEditorsOf(settings: WorkflowSettingsUseCase?): WorkflowEditorsImporter? =
    settings?.let(::SettingsWorkflowEditors)
