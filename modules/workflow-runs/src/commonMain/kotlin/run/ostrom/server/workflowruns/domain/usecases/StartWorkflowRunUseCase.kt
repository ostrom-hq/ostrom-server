// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.sessions.CloudProfileDirectory
import run.ostrom.server.kernel.sessions.has
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.models.RunLimitException
import run.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import run.ostrom.server.workflowruns.domain.models.StartRunForbiddenException
import run.ostrom.server.workflowruns.domain.models.WorkflowNotAcceptedException
import run.ostrom.server.workflowruns.domain.repositories.KeyedAdd
import run.ostrom.server.workflowruns.domain.repositories.KeyedRunsRepository
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.RunWorkflows
import kotlin.time.Clock
import kotlin.time.Instant
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
     * @param profileId The cloud profile of its agent nodes that name none, or null.
     *
     * @return The run as started: the workflow's name and current version, the person, `manual`.
     *
     * @throws StartRunForbiddenException When the caller is not an owner or an admin, checked before the workflow is
     *   looked up.
     * @throws RunWorkflowNotFoundException When the organization has no such workflow.
     * @throws WorkflowNotAcceptedException When the workflow is still a proposal.
     * @throws run.ostrom.server.workflowruns.domain.models.InvalidRunException When the inputs are not the
     *   workflow's ([RunInputs]), or the profile is none of the organization's.
     */
    suspend operator fun invoke(
        actor: GateActor,
        workflowId: String,
        inputs: Map<String, String>,
        person: Uuid? = null,
        profileId: String? = null,
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

    /**
     * Starts a run as [invoke] does, under the caller's `Idempotency-Key`: the same [key] gives back the run it
     * started first, never a second (the key is unique per caller), and a caller has at most [MAX_UNFINISHED]
     * unfinished runs, which an unkeyed start does not count.
     *
     * @param actor The caller: an owner or an admin of the organization, with a person's credential.
     * @param workflowId The workflow.
     * @param inputs The inputs of the request.
     * @param person The person's id, for the audit log.
     * @param key The idempotency key, already checked against `Idempotency.isValid`.
     * @param profileId The cloud profile of its agent nodes that name none, or null.
     *
     * @return The run, and whether an earlier start of the key started it.
     *
     * @throws StartRunForbiddenException When the caller is not an owner or an admin, checked before the workflow is
     *   looked up.
     * @throws RunWorkflowNotFoundException When the organization has no such workflow.
     * @throws WorkflowNotAcceptedException When the workflow is still a proposal.
     * @throws run.ostrom.server.workflowruns.domain.models.InvalidRunException When the inputs are not the
     *   workflow's ([RunInputs]), or the profile is none of the organization's.
     * @throws RunLimitException When the caller has [MAX_UNFINISHED] unfinished runs.
     */
    suspend fun startKeyed(
        actor: GateActor,
        workflowId: String,
        inputs: Map<String, String>,
        person: Uuid?,
        key: String,
        profileId: String? = null,
    ): StartedRun
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
 * @property profileId The cloud profile of its agent nodes that name none, or null.
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
    val profileId: String? = null,
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
 * @param profiles The organization's cloud profiles a manual start may name; null where sessions run on none.
 */
class StartWorkflowRunUseCaseImpl(
    private val workflows: RunWorkflows,
    private val repository: StartedRunsRepository,
    private val keyed: KeyedRunsRepository,
    private val clock: Clock,
    private val profiles: CloudProfileDirectory? = null,
) : StartWorkflowRunUseCase {
    override suspend fun invoke(
        actor: GateActor,
        workflowId: String,
        inputs: Map<String, String>,
        person: Uuid?,
        profileId: String?,
    ): WorkflowRun {
        val now = clock.now()
        val (run, graph, audit) = prepare(actor, workflowId, inputs, person, profileId, now)
        repository.add(actor.orgId, run, now, graph, audit)
        return run
    }

    override suspend fun startKeyed(
        actor: GateActor,
        workflowId: String,
        inputs: Map<String, String>,
        person: Uuid?,
        key: String,
        profileId: String?,
    ): StartedRun {
        val now = clock.now()
        val (run, graph, audit) = prepare(actor, workflowId, inputs, person, profileId, now)
        return when (val added = keyed.addKeyed(actor.orgId, run, now, graph, audit, key, MAX_UNFINISHED)) {
            is KeyedAdd.Added -> StartedRun(added.run, replayed = false)
            is KeyedAdd.Existing -> StartedRun(added.run, replayed = true)
            KeyedAdd.AtLimit -> throw RunLimitException(MAX_UNFINISHED)
        }
    }

    // What a manual start stores, once the caller, the workflow and the inputs are checked.
    @Suppress("ThrowsCount") // The three refusals of a start: who, which workflow, and whether it can run.
    private suspend fun prepare(
        actor: GateActor,
        workflowId: String,
        inputs: Map<String, String>,
        person: Uuid?,
        profileId: String?,
        now: Instant,
    ): Triple<WorkflowRun, RunGraph, RunAudit> {
        if (actor.kind != ActorKind.USER || actor.role !in STARTERS) throw StartRunForbiddenException()
        val workflow = workflows.find(actor.orgId, workflowId) ?: throw RunWorkflowNotFoundException()
        if (workflow.status !in RUNNABLE) throw WorkflowNotAcceptedException()
        val values = RunInputs.of(workflow.inputs, inputs)
        if (profileId != null) requireProfile(actor.orgId, profileId)
        val run = WorkflowRun(
            id = "wfr_" + Uuid.random().toHexString(),
            workflowId = workflow.id,
            workflowName = workflow.name,
            workflowVersion = workflow.version,
            status = RunStatus.QUEUED,
            startedBy = actor.id,
            source = MANUAL,
            inputs = values,
            profileId = profileId,
            startedAt = now.isoMillis(),
        )
        val audit = RunAudit("started", auditDetails("workflow" to workflow.id, "version" to workflow.version), person)
        return Triple(run, workflow.graph, audit)
    }

    // TeamCode refuses a profile it does not know at start; one the starter may not use fails its session later.
    private suspend fun requireProfile(orgId: Uuid, profileId: String) {
        val id = Uuid.parseOrNull(profileId) ?: throw InvalidRunException("profileId: a cloud profile's id (a UUID).")
        if (profiles?.has(orgId, id) == false) {
            throw InvalidRunException("profileId: no cloud profile of the organization has this id.")
        }
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
            profileId = start.profileId,
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
