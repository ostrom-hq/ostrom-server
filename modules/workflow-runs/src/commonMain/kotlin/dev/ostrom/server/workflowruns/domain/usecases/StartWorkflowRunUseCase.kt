// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.usecases

import dev.ostrom.core.api.models.RunStatus
import dev.ostrom.core.api.models.WorkflowRun
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import dev.ostrom.server.workflowruns.domain.models.StartRunForbiddenException
import dev.ostrom.server.workflowruns.domain.models.WorkflowNotAcceptedException
import dev.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import dev.ostrom.server.workflowruns.domain.services.RunWorkflows
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Starts a run of a workflow (contract `startWorkflowRun`). */
interface StartWorkflowRunUseCase {
    /**
     * Records the run, queued: no engine plays the runs yet (`.coord/QUESTIONS.md`, 03/11).
     *
     * @param actor The caller: an owner or an admin of the organization, with a person's credential.
     * @param workflowId The workflow.
     * @param inputs The inputs of the request.
     *
     * @return The run as started: the workflow's name and current version, the person, `manual`.
     *
     * @throws StartRunForbiddenException When the caller is not an owner or an admin, checked before the workflow is
     *   looked up.
     * @throws RunWorkflowNotFoundException When the organization has no such workflow.
     * @throws WorkflowNotAcceptedException When the workflow is still a proposal.
     * @throws dev.ostrom.server.workflowruns.domain.models.InvalidRunException When the inputs are not the
     *   workflow's ([RunInputs]).
     */
    suspend operator fun invoke(actor: GateActor, workflowId: String, inputs: Map<String, String>): WorkflowRun
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
    override suspend fun invoke(actor: GateActor, workflowId: String, inputs: Map<String, String>): WorkflowRun {
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
        repository.add(actor.orgId, run, now)
        return run
    }

    private companion object {
        val STARTERS = setOf(OrgRole.OWNER, OrgRole.ADMIN)
        val RUNNABLE = setOf(WorkflowStatus.ACCEPTED, WorkflowStatus.EDITED)
        const val MANUAL = "manual"
    }
}
