// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.repositories

import run.ostrom.server.compiler.domain.models.Authorship
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.StoredWorkflow
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * The most workflows an organization keeps: every read of them (the list, the gates catalogue, the approvers graph, a
 * compilation) loads them all, so a creation beyond is refused.
 */
const val MAX_WORKFLOWS = 1000

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
     * Counts the organization's workflows.
     *
     * @param orgId The organization.
     * @return How many it keeps, [MAX_WORKFLOWS] at most but for two creations at once.
     */
    suspend fun workflowCount(orgId: Uuid): Int

    /**
     * Lists the last decision taken on each of the organization's workflows: what a package cites. Never the whole
     * log, which grows with every save.
     *
     * @param orgId The organization.
     * @return One decision per workflow that has one.
     */
    suspend fun lastDecisions(orgId: Uuid): List<DecisionDoc>

    /**
     * Records a decision.
     *
     * @param orgId The organization.
     * @param decision The decision.
     */
    suspend fun addDecision(orgId: Uuid, decision: DecisionDoc)
}
