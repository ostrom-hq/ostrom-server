// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.repositories

import run.ostrom.core.api.models.WorkflowEditors
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Who may decide an organization's workflows (`workflows.settings.v1`). */
interface WorkflowSettingsRepository {
    /**
     * Reads the editing rule.
     *
     * @param orgId The organization.
     * @return The rule, or null when the organization never set one (owners and admins only).
     */
    suspend fun editors(orgId: Uuid): WorkflowEditors?

    /**
     * Stores the editing rule, replacing the one before.
     *
     * @param orgId The organization.
     * @param editors The rule.
     * @param by Who changed it (a login).
     * @param at When.
     */
    suspend fun saveEditors(orgId: Uuid, editors: WorkflowEditors, by: String, at: Instant)
}
