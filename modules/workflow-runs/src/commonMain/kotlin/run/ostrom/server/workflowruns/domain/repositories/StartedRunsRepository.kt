// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.repositories

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunGraph
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A page of the runs of workflows (contract `WorkflowRun`), not the check runs of a session.
 *
 * @property workflowId Only this workflow's, or every one.
 * @property status Only these, or every one.
 * @property before The id the previous page ended on, or null for the newest.
 * @property limit At most this many.
 */
data class RunsQuery(
    val workflowId: String? = null,
    val status: RunStatus? = null,
    val before: String? = null,
    val limit: Int = RunQuery.PAGE,
)

/** Storage of the runs of workflows, isolated by organization. */
interface StartedRunsRepository {
    /**
     * Stores a run as it starts, due at once for the engine, with its audit line in the same transaction.
     *
     * @param orgId The organization.
     * @param run The run.
     * @param startedAt When it started, to the precision the database keeps (the run says it to the millisecond).
     * @param graph The graph it plays, copied from its workflow.
     * @param audit Its audit line.
     */
    suspend fun add(orgId: Uuid, run: WorkflowRun, startedAt: Instant, graph: RunGraph, audit: RunAudit)

    /**
     * Stores the child run a visit of its parent ([WorkflowRun.parentRunId]) starts, due at once for the engine, with
     * its audit line, unless that visit already started one: one child per visit, whatever restarts.
     *
     * @param orgId The organization.
     * @param child The child run.
     * @param seq The parent's visit.
     * @param graph The graph it plays, copied from its workflow.
     * @param audit Its audit line, written only when the child is stored.
     *
     * @return The id of the visit's child: [child]'s, or the one stored before.
     */
    suspend fun addChild(orgId: Uuid, child: WorkflowRun, seq: Int, graph: RunGraph, audit: RunAudit): String

    /**
     * Reads a run.
     *
     * @param orgId The organization.
     * @param id The run.
     *
     * @return The run, or null when the organization has none of that id.
     */
    suspend fun find(orgId: Uuid, id: String): WorkflowRun?

    /**
     * Lists runs, newest first.
     *
     * @param orgId The organization.
     * @param query Which.
     *
     * @return The runs.
     */
    suspend fun runs(orgId: Uuid, query: RunsQuery): List<WorkflowRun>
}
