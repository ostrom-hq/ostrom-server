// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.repositories

import dev.ostrom.core.api.models.RunStatus
import dev.ostrom.core.api.models.WorkflowRun
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
     * Stores a run as it starts.
     *
     * @param orgId The organization.
     * @param run The run.
     * @param startedAt When it started, to the precision the database keeps (the run says it to the millisecond).
     */
    suspend fun add(orgId: Uuid, run: WorkflowRun, startedAt: Instant)

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
