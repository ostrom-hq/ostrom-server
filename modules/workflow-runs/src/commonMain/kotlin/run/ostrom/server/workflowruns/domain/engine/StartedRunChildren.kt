// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.engine

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunWorkflow
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.ChildOpening
import run.ostrom.server.workflowruns.domain.services.ChildRequest
import run.ostrom.server.workflowruns.domain.services.ChildResult
import run.ostrom.server.workflowruns.domain.services.RunChildren
import run.ostrom.server.workflowruns.domain.services.RunWorkflows
import run.ostrom.server.workflowruns.domain.usecases.RunInputs
import run.ostrom.server.workflowruns.domain.usecases.cancelledRun
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [RunChildren] on the runs: a child run is a run of another workflow of the organization, stored as a start stores
 * one (queued, its workflow's graph copied, its audit line) with its parent and the parent's visit, so the same engine
 * plays it and a visit played again finds it. A chain of runs nests at most [MAX_DEPTH] deep and never runs a workflow
 * already running above (no cycle); the child runs as the parent's starter, with the node's arguments as its inputs,
 * rendered on the parent run ([RunTemplates]: its inputs and its nodes' outputs).
 *
 * @param workflows The organization's workflows.
 * @param runs The runs, to store and read one.
 * @param engine The runs, to cancel one.
 * @param clock The clock.
 */
class StartedRunChildren(
    private val workflows: RunWorkflows,
    private val runs: StartedRunsRepository,
    private val engine: RunEngineRepository,
    private val clock: Clock,
) : RunChildren {
    override suspend fun start(orgId: Uuid, request: ChildRequest): ChildOpening {
        val workflow = workflows.find(orgId, request.workflowId)
        val refusal = refusal(lineage(orgId, request.parent), request.workflowId, workflow)
        val accepted = if (refusal == null) workflow else null
        return try {
            if (accepted == null) ChildOpening(refusal = refusal) else started(orgId, request, accepted)
        } catch (invalid: InvalidRunException) {
            ChildOpening(refusal = invalid.message)
        }
    }

    override suspend fun result(orgId: Uuid, runId: String): ChildResult? =
        runs.find(orgId, runId)?.let { ChildResult(it.status, it.failure, it.output) }

    override suspend fun cancel(orgId: Uuid, runId: String) {
        repeat(CANCEL_ATTEMPTS) {
            val run = runs.find(orgId, runId)
            if (run == null || run.status in ENDED_RUNS) return
            val now = clock.now()
            val audit = RunAudit("cancelled", auditDetails("by" to "parent", "parent" to run.parentRunId))
            val change = RunChange(run, cancelledRun(run, now.isoMillis()), null, audit = listOf(audit), dueAt = now)
            if (engine.write(orgId, change)) return
        }
    }

    // The workflows of the runs above a child, its parent's first: read up to the most a chain nests.
    private suspend fun lineage(orgId: Uuid, parent: WorkflowRun): List<String> {
        val lineage = mutableListOf<String>()
        var run: WorkflowRun? = parent
        while (run != null && lineage.size < MAX_DEPTH) {
            lineage += run.workflowId
            run = run.parentRunId?.let { runs.find(orgId, it) }
        }
        return lineage
    }

    private fun refusal(lineage: List<String>, workflowId: String, workflow: RunWorkflow?): String? = when {
        workflowId in lineage -> "Workflow ${workflowId.take(ID)} already runs above this node: a workflow never " +
            "calls itself."
        lineage.size >= MAX_DEPTH -> "A run nests at most $MAX_DEPTH runs deep."
        workflow == null -> "Workflow ${workflowId.take(ID)} is not in the organization."
        workflow.status !in RUNNABLE -> "Workflow ${workflow.id} is still a proposal."
        else -> null
    }

    private suspend fun started(orgId: Uuid, request: ChildRequest, workflow: RunWorkflow): ChildOpening {
        val parent = request.parent
        val arguments = request.arguments.mapValues { (_, value) -> RunTemplates.render(value, parent) }
        val child = WorkflowRun(
            id = "wfr_" + Uuid.random().toHexString(),
            workflowId = workflow.id,
            workflowName = workflow.name,
            workflowVersion = workflow.version,
            status = RunStatus.QUEUED,
            startedBy = parent.startedBy,
            source = SOURCE,
            inputs = RunInputs.of(workflow.inputs, arguments),
            parentRunId = parent.id,
            startedAt = clock.now().isoMillis(),
        )
        val details = auditDetails("workflow" to workflow.id, "version" to workflow.version, "parent" to parent.id)
        val audit = RunAudit("started", details)
        return ChildOpening(runId = runs.addChild(orgId, child, request.seq, workflow.graph, audit))
    }

    /** The bounds of the child runs. */
    companion object {
        /** The most runs a chain nests, the first run included. */
        const val MAX_DEPTH: Int = 5
    }
}

// The source a child run says, the workflows a run starts, how often a cancel reads the child again when the engine
// changed it meanwhile, the runs already ended, and the most of an id a refusal shows.
private const val SOURCE = "workflow"
private const val CANCEL_ATTEMPTS = 3
private const val ID = 200
private val RUNNABLE = setOf(WorkflowStatus.ACCEPTED, WorkflowStatus.EDITED)
private val ENDED_RUNS = setOf(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED)
