// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.repositories

import dev.ostrom.server.compiler.domain.models.Authorship
import dev.ostrom.server.compiler.domain.models.DecisionDoc
import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** The organization's workflows and the decisions taken on them. */
interface WorkflowsRepository {
    /**
     * Lists an organization's workflows.
     *
     * @param orgId The organization.
     * @return The workflows, oldest first.
     */
    suspend fun workflows(orgId: Uuid): List<StoredWorkflow>

    /**
     * Reads a workflow.
     *
     * @param orgId The organization.
     * @param id The workflow.
     * @return The workflow, or null.
     */
    suspend fun workflow(orgId: Uuid, id: String): StoredWorkflow?

    /**
     * Creates a workflow ([expectedVersion] null) if its id is free, or replaces it if its version is still
     * [expectedVersion]: a write never creates what an update names, nor replaces what a creation names.
     *
     * @param orgId The organization.
     * @param id The workflow.
     * @param raw Its document.
     * @param expectedVersion The version the editor read, or null for a new workflow.
     * @param author Who saves, and when.
     * @return The saved workflow, or null when the id is taken (a creation) or someone saved another version, or
     *   deleted it, in between (an update).
     */
    suspend fun saveWorkflow(
        orgId: Uuid,
        id: String,
        raw: JsonObject,
        expectedVersion: Int?,
        author: Authorship,
    ): StoredWorkflow?

    /**
     * Deletes a workflow.
     *
     * @param orgId The organization.
     * @param id The workflow.
     * @return False when there was none.
     */
    suspend fun deleteWorkflow(orgId: Uuid, id: String): Boolean

    /**
     * Lists the decisions taken on workflows.
     *
     * @param orgId The organization.
     * @return The decisions.
     */
    suspend fun decisions(orgId: Uuid): List<DecisionDoc>

    /**
     * Records a decision.
     *
     * @param orgId The organization.
     * @param decision The decision.
     */
    suspend fun addDecision(orgId: Uuid, decision: DecisionDoc)
}
