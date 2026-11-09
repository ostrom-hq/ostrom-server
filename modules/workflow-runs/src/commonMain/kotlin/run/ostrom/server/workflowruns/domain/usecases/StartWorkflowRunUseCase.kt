// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import run.ostrom.server.workflowruns.domain.models.StartRunForbiddenException
import run.ostrom.server.workflowruns.domain.models.WorkflowNotAcceptedException
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.RunWorkflows
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Starts a run of a workflow (contract `startWorkflowRun`). */
interface StartWorkflowRunUseCase {
    /**
     * Records the run, queued, with the graph of its workflow as it is now: the engine plays it from there.
     *
     * @param actor The caller: an owner or an admin of the organization, with a person's credential.
     * @param workflowId The workflow.
     * @param inputs The inputs of the request.
     * @param person The person's id, for the audit log.
     *
     * @return The run as started: the workflow's name and current version, the person, `manual`.
     *
     * @throws StartRunForbiddenException When the caller is not an owner or an admin, checked before the workflow is
     *   looked up.
     * @throws RunWorkflowNotFoundException When the organization has no such workflow.
     * @throws WorkflowNotAcceptedException When the workflow is still a proposal.
     * @throws run.ostrom.server.workflowruns.domain.models.InvalidRunException When the inputs are not the
     *   workflow's ([RunInputs]).
     */
    suspend operator fun invoke(
        actor: GateActor,
        workflowId: String,
        inputs: Map<String, String>,
        person: Uuid? = null,
    ): WorkflowRun
}

/**
 * [StartWorkflowRunUseCase] on the repository.
 *
 * @param workflows The organization's workflows.
 * @param repository The runs.
 * @param clock The clock.
 */
class StartWorkflowRunUseCaseImpl(
    private val workflows: RunWorkflows,
    private val repository: StartedRunsRepository,
    private val clock: Clock,
) : StartWorkflowRunUseCase {
    override suspend fun invoke(
        actor: GateActor,
        workflowId: String,
        inputs: Map<String, String>,
        person: Uuid?,
    ): WorkflowRun {
        if (actor.kind != ActorKind.USER || actor.role !in STARTERS) throw StartRunForbiddenException()
        val workflow = workflows.find(actor.orgId, workflowId) ?: throw RunWorkflowNotFoundException()
        if (workflow.status !in RUNNABLE) throw WorkflowNotAcceptedException()
        val values = RunInputs.of(workflow.inputs, inputs)
        val now = clock.now()
        val run = WorkflowRun(
            id = "wfr_" + Uuid.random().toHexString(),
            workflowId = workflow.id,
            workflowName = workflow.name,
            workflowVersion = workflow.version,
            status = RunStatus.QUEUED,
            startedBy = actor.id,
            source = MANUAL,
            inputs = values,
            startedAt = now.isoMillis(),
        )
        val audit = RunAudit("started", auditDetails("workflow" to workflow.id, "version" to workflow.version), person)
        repository.add(actor.orgId, run, now, workflow.graph, audit)
        return run
    }
}

// Who starts a run, of which workflows, and the source a run started from the API says.
private val STARTERS = setOf(OrgRole.OWNER, OrgRole.ADMIN)
private val RUNNABLE = setOf(WorkflowStatus.ACCEPTED, WorkflowStatus.EDITED)
private const val MANUAL = "manual"
