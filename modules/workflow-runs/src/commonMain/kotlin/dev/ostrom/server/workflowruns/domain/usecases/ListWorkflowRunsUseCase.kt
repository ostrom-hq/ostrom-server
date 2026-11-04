// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.usecases

import dev.ostrom.core.api.models.WorkflowRun
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import dev.ostrom.server.workflowruns.domain.repositories.RunsQuery
import dev.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import dev.ostrom.server.workflowruns.domain.services.RunWorkflows

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
        return repository.runs(actor.orgId, query)
    }
}
