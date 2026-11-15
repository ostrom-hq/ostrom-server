// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.repositories

import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunGraph
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What a keyed start stored. */
sealed interface KeyedAdd {
    /** The run was stored. */
    data class Added(val run: WorkflowRun) : KeyedAdd

    /** The starter had already started [run] under this key: nothing was stored. */
    data class Existing(val run: WorkflowRun) : KeyedAdd

    /** The starter already has the most unfinished runs: nothing was stored. */
    data object AtLimit : KeyedAdd
}

/** Stores the runs a member starts, under an idempotency key or not, within their cap of unfinished runs. */
interface KeyedRunsRepository {
    /**
     * Stores a run as [StartedRunsRepository.add] does, unless its starter ([WorkflowRun.startedBy]) already started
     * one under [key] (that run is returned), or already has [maxUnfinished] unfinished runs (TeamCode's `run_limit`,
     * on every start of a member). Those count whatever
     * started them (by hand, this or another automation), child runs of a workflow run aside.
     * The look-up, the count and the write are one decision, whatever replica runs another start of the same starter.
     *
     * @param orgId The organization.
     * @param run The run.
     * @param startedAt When it started.
     * @param graph The graph it plays.
     * @param audit Its audit line, written only when the run is stored.
     * @param key The idempotency key, unique per starter, or null for a start without one.
     * @param maxUnfinished The starter's cap.
     *
     * @return What was stored, or found.
     */
    @Suppress("LongParameterList") // A start's run, its graph and audit, and the key and cap it is checked against.
    suspend fun addKeyed(
        orgId: Uuid,
        run: WorkflowRun,
        startedAt: Instant,
        graph: RunGraph,
        audit: RunAudit,
        key: String?,
        maxUnfinished: Int,
    ): KeyedAdd
}
