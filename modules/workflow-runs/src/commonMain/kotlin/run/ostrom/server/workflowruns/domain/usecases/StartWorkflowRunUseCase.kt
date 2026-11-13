// SPDX-License-Identifier: Apache-2.0
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
import run.ostrom.server.workflowruns.domain.models.RunLimitException
import run.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import run.ostrom.server.workflowruns.domain.models.StartRunForbiddenException
import run.ostrom.server.workflowruns.domain.models.WorkflowNotAcceptedException
import run.ostrom.server.workflowruns.domain.repositories.KeyedAdd
import run.ostrom.server.workflowruns.domain.repositories.KeyedRunsRepository
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

    /**
     * Starts a run as a member, for what acts in their name (an automation's workflow action, TC-AUT-5): any member,
     * as the decision's "any member starts an accepted workflow"; the same [MemberStart.key] gives back the run it
     * started first, never a second (`Idempotency-Key`); a member has at most [MAX_UNFINISHED] unfinished runs.
     *
     * @param start Who starts what.
     *
     * @return The run, and whether it is the one an earlier start of the key started.
     *
     * @throws RunWorkflowNotFoundException When the organization has no such workflow.
     * @throws WorkflowNotAcceptedException When the workflow is still a proposal.
     * @throws run.ostrom.server.workflowruns.domain.models.InvalidRunException When the inputs are not the
     *   workflow's ([RunInputs]).
     * @throws RunLimitException When the member has [MAX_UNFINISHED] unfinished runs.
     */
    suspend fun startFor(start: MemberStart): StartedRun
}

/**
 * A run a member starts through what acts in their name.
 *
 * @property orgId The organization.
 * @property starter The member's login: the run's `startedBy`, whose sessions its agent nodes are.
 * @property workflowId The workflow.
 * @property inputs The inputs.
 * @property repository The repository of its agent nodes that name none.
 * @property key The idempotency key, unique per starter.
 * @property source What started it (`automation`).
 * @property automationId The automation that started it, if any.
 */
data class MemberStart(
    val orgId: Uuid,
    val starter: String,
    val workflowId: String,
    val inputs: Map<String, String>,
    val repository: String?,
    val key: String,
    val source: String,
    val automationId: String?,
)

/**
 * A run [StartWorkflowRunUseCase.startFor] gives.
 *
 * @property run The run.
 * @property replayed Whether an earlier start of the same key started it.
 */
data class StartedRun(val run: WorkflowRun, val replayed: Boolean)

/** How many unfinished runs (queued, running or waiting, child runs aside) a member has at most. */
const val MAX_UNFINISHED: Int = 20

/**
 * [StartWorkflowRunUseCase] on the repository.
 *
 * @param workflows The organization's workflows.
 * @param repository The runs.
 * @param keyed The runs started under an idempotency key.
 * @param clock The clock.
 */
class StartWorkflowRunUseCaseImpl(
    private val workflows: RunWorkflows,
    private val repository: StartedRunsRepository,
    private val keyed: KeyedRunsRepository,
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

    override suspend fun startFor(start: MemberStart): StartedRun {
        val workflow = workflows.find(start.orgId, start.workflowId) ?: throw RunWorkflowNotFoundException()
        if (workflow.status !in RUNNABLE) throw WorkflowNotAcceptedException()
        val values = RunInputs.of(workflow.inputs, start.inputs)
        val now = clock.now()
        val run = WorkflowRun(
            id = "wfr_" + Uuid.random().toHexString(),
            workflowId = workflow.id,
            workflowName = workflow.name,
            workflowVersion = workflow.version,
            status = RunStatus.QUEUED,
            startedBy = start.starter,
            source = start.source,
            automationId = start.automationId,
            inputs = values,
            repository = start.repository,
            startedAt = now.isoMillis(),
        )
        val details = auditDetails("workflow" to workflow.id, "version" to workflow.version, "source" to start.source)
        val audit = RunAudit("started", details, null)
        val added = keyed.addKeyed(start.orgId, run, now, workflow.graph, audit, start.key, MAX_UNFINISHED)
        return when (added) {
            is KeyedAdd.Added -> StartedRun(added.run, replayed = false)
            is KeyedAdd.Existing -> StartedRun(added.run, replayed = true)
            KeyedAdd.AtLimit -> throw RunLimitException(MAX_UNFINISHED)
        }
    }
}

// Who starts a run, of which workflows, and the source a run started from the API says.
private val STARTERS = setOf(OrgRole.OWNER, OrgRole.ADMIN)
private val RUNNABLE = setOf(WorkflowStatus.ACCEPTED, WorkflowStatus.EDITED)
private const val MANUAL = "manual"
