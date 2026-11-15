// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.kernel.errors.Pages
import run.ostrom.server.workflowruns.domain.models.ReadAllRunsForbiddenException
import run.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import run.ostrom.server.workflowruns.domain.repositories.RunsQuery
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.RunWorkflows

/** Lists the runs of workflows (contract `listWorkflowRuns`: any member's; `listRuns`: one's own, or everyone's). */
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

    /**
     * Lists the caller's own runs, newest first, or with [all] every member's (TeamCode's
     * `GET /api/v1/workflows/runs`): an owner, an admin or a service key of the organization.
     *
     * @param actor The caller.
     * @param query Which: one workflow's, one status, a page.
     * @param all Every member's runs instead of the caller's.
     *
     * @return The runs.
     *
     * @throws ReadAllRunsForbiddenException When [all] is asked by someone else.
     * @throws RunWorkflowNotFoundException When the query names a workflow the organization does not have.
     * @throws InvalidPageException When `before` names no run of the organization.
     */
    suspend fun mine(actor: GateActor, query: RunsQuery, all: Boolean): List<WorkflowRun>
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

    override suspend fun mine(actor: GateActor, query: RunsQuery, all: Boolean): List<WorkflowRun> {
        if (!all) return invoke(actor, query.copy(startedBy = actor.id))
        val reader = actor.kind == ActorKind.SERVICE || (actor.kind == ActorKind.USER && actor.role in READERS)
        if (!reader) throw ReadAllRunsForbiddenException()
        return invoke(actor, query)
    }
}

/** Who reads every member's runs, with a person's credential. */
private val READERS = setOf(OrgRole.OWNER, OrgRole.ADMIN)
