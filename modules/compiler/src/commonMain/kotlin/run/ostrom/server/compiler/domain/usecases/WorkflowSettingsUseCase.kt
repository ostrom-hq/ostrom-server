// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.usecases

import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.core.api.models.WorkflowSettings
import run.ostrom.server.compiler.domain.repositories.WorkflowSettingsRepository
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Who may decide workflows, besides owners and admins (TeamCode `workflow_settings`, docs/workflows.md "Editing
 * rights"): every member (`all`), owners and admins only (`admins`, the default), or the members listed (`members`).
 * An editor accepts, edits and deletes workflows as an owner or an admin does; a workflow that holds a gate node stays
 * an owner's or an admin's decision, whatever the rule: a gate is Ostrom's security boundary.
 */
interface WorkflowSettingsUseCase {
    /**
     * Reads the rule and whether the caller may decide a workflow without a gate node.
     *
     * @param orgId The organization.
     * @param login The caller's login.
     * @param role The caller's role, or null for one who is not a person (an agent).
     * @return The settings.
     */
    suspend fun settings(orgId: Uuid, login: String, role: OrgRole?): WorkflowSettings

    /**
     * Changes the rule, audited as `workflow.editors.changed`.
     *
     * @param orgId The organization.
     * @param login The caller's login.
     * @param role The caller's role.
     * @param principal The caller's credential, named in the audit log.
     * @param editors The new rule.
     * @return The settings, as read after.
     * @throws OstromException A 403 for anyone but an owner or an admin; a 400 `invalid_editors` for an unknown mode,
     *   a list outside the `members` mode, more than 100 editors, or an editor who is not an active member.
     */
    suspend fun save(
        orgId: Uuid,
        login: String,
        role: OrgRole?,
        principal: AuthenticatedPrincipal,
        editors: WorkflowEditors,
    ): WorkflowSettings

    /**
     * Whether the caller may decide (accept, edit, delete) a workflow.
     *
     * @param orgId The organization.
     * @param login The caller's login.
     * @param role The caller's role, or null for one who is not a person.
     * @param gated True when the workflow holds a gate node, before or after the change.
     * @return True for an owner or an admin; for an editor, when the workflow holds no gate; false otherwise.
     */
    suspend fun mayDecide(orgId: Uuid, login: String, role: OrgRole?, gated: Boolean): Boolean
}

/**
 * [WorkflowSettingsUseCase] on the settings' storage and the organization's members.
 *
 * @param repository The stored rule.
 * @param members Tells who is an active member.
 * @param logins Gives a member's id from their login.
 * @param audit The organization's audit log.
 * @param clock The time.
 */
class WorkflowSettingsUseCaseImpl(
    private val repository: WorkflowSettingsRepository,
    private val members: MemberDirectory,
    private val logins: MemberLogins,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : WorkflowSettingsUseCase {
    override suspend fun settings(orgId: Uuid, login: String, role: OrgRole?): WorkflowSettings {
        val editors = rule(orgId)
        return WorkflowSettings(editors, canEdit = editor(orgId, login, role, editors))
    }

    override suspend fun save(
        orgId: Uuid,
        login: String,
        role: OrgRole?,
        principal: AuthenticatedPrincipal,
        editors: WorkflowEditors,
    ): WorkflowSettings {
        if (role !in MANAGERS) {
            throw OstromException(ErrorKind.FORBIDDEN, MANAGERS_ONLY, type = EDITORS_ONLY)
        }
        val checked = checked(orgId, editors)
        repository.saveEditors(orgId, checked, login, clock.now())
        val facts = auditDetails("mode" to checked.mode, "editors" to checked.memberIds?.size)
        audit.record(OrgAuditRecord(orgId, principal, "workflow.editors.changed", "workflow_settings", "$orgId", facts))
        return WorkflowSettings(checked, canEdit = true)
    }

    override suspend fun mayDecide(orgId: Uuid, login: String, role: OrgRole?, gated: Boolean): Boolean = when {
        role in MANAGERS -> true
        gated -> false
        else -> editor(orgId, login, role, rule(orgId))
    }

    // What is stored; owners and admins only until an organization says otherwise.
    private suspend fun rule(orgId: Uuid): WorkflowEditors = repository.editors(orgId) ?: WorkflowEditors(ADMINS)

    // Whether this person, who is not an owner or an admin, is let in by the rule.
    private suspend fun editor(orgId: Uuid, login: String, role: OrgRole?, rule: WorkflowEditors): Boolean = when {
        role == null -> false
        role in MANAGERS -> true
        rule.mode == ALL -> true
        rule.mode == MEMBERS -> logins.idsOf(orgId, listOf(login))[login]?.toString() in rule.memberIds.orEmpty()
        else -> false
    }

    private suspend fun checked(orgId: Uuid, editors: WorkflowEditors): WorkflowEditors {
        val ids = editors.memberIds.orEmpty().distinct()
        invalid(editors.mode !in MODES, "mode is all, admins or members.")
        invalid(editors.mode != MEMBERS && ids.isNotEmpty(), "Only the members mode has a list of members.")
        invalid(ids.size > MAX_EDITORS, "At most $MAX_EDITORS editors.")
        invalid(ids.any { !members.isActiveMember(orgId, it) }, "Editors are active members of the organization.")
        return WorkflowEditors(editors.mode, ids.takeIf { editors.mode == MEMBERS })
    }

    private fun invalid(refused: Boolean, reason: String) {
        if (refused) throw OstromException(ErrorKind.INVALID_REQUEST, reason, type = "invalid_editors")
    }

    /** The rule's vocabulary and bounds. */
    companion object {
        /** Every member. */
        const val ALL: String = "all"

        /** Owners and admins only, the default. */
        const val ADMINS: String = "admins"

        /** The members listed. */
        const val MEMBERS: String = "members"

        /** The most editors listed. */
        const val MAX_EDITORS: Int = 100

        /** The `error.type` of a decision someone may not make. */
        const val EDITORS_ONLY: String = "workflow_editors_only"

        private val MODES = setOf(ALL, ADMINS, MEMBERS)
        private val MANAGERS = setOf(OrgRole.OWNER, OrgRole.ADMIN)
        private const val MANAGERS_ONLY = "Only an owner or an admin changes who may edit workflows."
    }
}
