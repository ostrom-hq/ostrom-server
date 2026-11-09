// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.kernel.errors.Pages
import run.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import run.ostrom.server.workflowruns.domain.repositories.RunsQuery
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.RunWorkflows

/** Lists the runs of workflows (contract `listWorkflowRuns`, `listRuns`): any member's. */
interface ListWorkflowRunsUseCase {
    /**
     * Lists the runs, newest first.
     *
     * @param actor The caller.
     * @param query Which: one workflow's, or the organization's.
     *
     * @return The runs.
     *
     * @throws RunWorkflowNotFoundException When the query names a workflow the organization does not have.
     * @throws InvalidPageException When `before` names no run of the organization (garbage, forged, another's).
     */
    suspend operator fun invoke(actor: GateActor, query: RunsQuery): List<WorkflowRun>
}

/**
 * [ListWorkflowRunsUseCase] on the repository.
 *
 * @param workflows The organization's workflows.
 * @param repository The runs.
 */
class ListWorkflowRunsUseCaseImpl(
    private val workflows: RunWorkflows,
    private val repository: StartedRunsRepository,
) : ListWorkflowRunsUseCase {
    override suspend fun invoke(actor: GateActor, query: RunsQuery): List<WorkflowRun> {
        val workflowId = query.workflowId
        if (workflowId != null && workflows.find(actor.orgId, workflowId) == null) {
            throw RunWorkflowNotFoundException()
        }
        // An empty page after a cursor costs one read by key: a run of this organization, or a 400.
        return Pages.checked("before", query.before, repository.runs(actor.orgId, query)) {
            repository.find(actor.orgId, it) != null
        }
    }
}
