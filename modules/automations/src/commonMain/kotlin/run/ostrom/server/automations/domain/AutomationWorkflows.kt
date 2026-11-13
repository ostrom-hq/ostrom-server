// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import kotlin.uuid.Uuid

/**
 * An input a workflow declares, as a workflow action is checked against it at save.
 *
 * @property id Its id.
 * @property required Whether a run needs a value.
 * @property hasDefault Whether it has a default, which a missing value takes.
 */
data class WorkflowInputSpec(val id: String, val required: Boolean, val hasDefault: Boolean)

/**
 * A workflow run to start for an automation run, as the automation's owner.
 *
 * @property orgId The organization.
 * @property owner The member it runs as (their member id).
 * @property automationId The automation (recorded on the workflow run).
 * @property workflowId The workflow.
 * @property inputs The rendered inputs, the empty ones left out.
 * @property repository The repository of its agent nodes that name none.
 * @property idempotencyKey `automation-run:<run id>`: a start retried, or replayed after a crash, finds the first
 *   workflow run of this key instead of starting a second.
 */
data class WorkflowStartRequest(
    val orgId: Uuid,
    val owner: String,
    val automationId: Uuid,
    val workflowId: String,
    val inputs: Map<String, String>,
    val repository: String?,
    val idempotencyKey: String,
)

/** What starting a workflow run gave. */
sealed interface WorkflowStart {
    /** The workflow run exists (started now, or by an earlier start of the same key). */
    data class Started(val workflowRunId: String) : WorkflowStart

    /**
     * It was refused; [reason] is a stable code: `run_limit` (the owner has 20 unfinished runs), `workflow_missing`,
     * `invalid_inputs`, `owner_unavailable`, or another refusal's code.
     */
    data class Refused(val reason: String) : WorkflowStart
}

/** Where a workflow run an automation started is. */
enum class WorkflowRunState {
    /** Queued, running or waiting for a person. */
    RUNNING,

    /** It ended well. */
    SUCCEEDED,

    /** It failed. */
    FAILED,

    /** It was cancelled. */
    CANCELLED,
}

/** The workflow runs of workflow actions (TC-AUT-5); bound by the module that runs workflows. */
interface AutomationWorkflows {
    /**
     * The inputs of a workflow a run may start (accepted or edited), for the checks at save.
     *
     * @return Its inputs, or null when the organization has no such workflow, or only a proposal.
     */
    suspend fun inputs(orgId: Uuid, workflowId: String): List<WorkflowInputSpec>?

    /** Starts a workflow run, or finds the one an earlier start of the same key started. */
    suspend fun start(request: WorkflowStartRequest): WorkflowStart

    /** Where a workflow run is; null when it no longer exists. */
    suspend fun state(orgId: Uuid, workflowRunId: String): WorkflowRunState?

    /**
     * Cancels a workflow run as [owner] (a cancelled automation run).
     *
     * @return False when it is over already, or gone, so there was nothing to cancel.
     */
    suspend fun cancel(orgId: Uuid, owner: String, workflowRunId: String): Boolean
}

/** No module runs workflows: no workflow exists for an action, and a start is refused `workflows_unavailable`. */
object NoWorkflows : AutomationWorkflows {
    override suspend fun inputs(orgId: Uuid, workflowId: String): List<WorkflowInputSpec>? = null

    override suspend fun start(request: WorkflowStartRequest): WorkflowStart =
        WorkflowStart.Refused("workflows_unavailable")

    override suspend fun state(orgId: Uuid, workflowRunId: String): WorkflowRunState? = null

    override suspend fun cancel(orgId: Uuid, owner: String, workflowRunId: String): Boolean = false
}
