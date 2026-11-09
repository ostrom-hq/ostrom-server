// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.workflowruns.domain.engine.RunMoves
import run.ostrom.server.workflowruns.domain.models.CancelRunForbiddenException
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunBusyException
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunEndedException
import run.ostrom.server.workflowruns.domain.models.RunNotFoundException
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Reads a run of a workflow with its visits (contract `getRun`): any member's. */
interface GetWorkflowRunUseCase {
    /**
     * Reads the run.
     *
     * @param actor The caller.
     * @param id The run.
     *
     * @return The run.
     *
     * @throws RunNotFoundException When the organization has no such run.
     */
    suspend operator fun invoke(actor: GateActor, id: String): WorkflowRun
}

/**
 * [GetWorkflowRunUseCase] on the repository.
 *
 * @param repository The runs.
 */
class GetWorkflowRunUseCaseImpl(private val repository: StartedRunsRepository) : GetWorkflowRunUseCase {
    override suspend fun invoke(actor: GateActor, id: String): WorkflowRun =
        repository.find(actor.orgId, id) ?: throw RunNotFoundException()
}

/** Cancels a run of a workflow (contract `cancelRun`). */
interface CancelWorkflowRunUseCase {
    /**
     * Cancels the run: it ends `cancelled`, its visit under way is `failed` with the outcome `cancelled`, and the
     * engine looks at it once more to end the agent session it started. Nothing of it is played afterwards.
     *
     * @param actor The caller: an owner or an admin of the organization, with a person's credential.
     * @param id The run.
     * @param person The person's id, for the audit log.
     *
     * @return The run, cancelled.
     *
     * @throws CancelRunForbiddenException When the caller is not an owner or an admin, checked before the run is
     *   looked up.
     * @throws RunNotFoundException When the organization has no such run.
     * @throws RunEndedException When the run already ended.
     * @throws RunBusyException When the engine kept changing the run while it was cancelled.
     */
    suspend operator fun invoke(actor: GateActor, id: String, person: Uuid?): WorkflowRun
}

/**
 * [CancelWorkflowRunUseCase] on the repositories: the change is written only if the run is still as read, so the
 * engine's step and the cancel never overwrite each other; a step written meanwhile is read again.
 *
 * @param runs The runs, to read one.
 * @param engine The runs, to change one.
 * @param clock The clock.
 */
class CancelWorkflowRunUseCaseImpl(
    private val runs: StartedRunsRepository,
    private val engine: RunEngineRepository,
    private val clock: Clock,
) : CancelWorkflowRunUseCase {
    override suspend fun invoke(actor: GateActor, id: String, person: Uuid?): WorkflowRun {
        requireStarter(actor)
        repeat(ATTEMPTS) {
            val run = current(actor.orgId, id)
            val now = clock.now()
            val next = cancelledRun(run, now.isoMillis())
            val audit = RunAudit("cancelled", auditDetails("by" to actor.id, "node" to run.currentNodeId), person)
            if (engine.write(actor.orgId, RunChange(run, next, null, audit = listOf(audit), dueAt = now))) return next
        }
        throw RunBusyException()
    }

    private suspend fun current(orgId: Uuid, id: String): WorkflowRun {
        val run = runs.find(orgId, id) ?: throw RunNotFoundException()
        if (run.status in ENDED) throw RunEndedException()
        return run
    }

    private fun requireStarter(actor: GateActor) {
        if (actor.kind != ActorKind.USER || actor.role !in CANCELLERS) throw CancelRunForbiddenException()
    }
}

/**
 * A run cancelled: it ends `cancelled`, and its visit under way is `failed` with the outcome `cancelled`.
 *
 * @param run The run.
 * @param at When, to the millisecond.
 *
 * @return The run, cancelled.
 */
fun cancelledRun(run: WorkflowRun, at: String): WorkflowRun {
    val visits = run.visits.map { visit ->
        if (visit.status in UNDER_WAY) {
            visit.copy(status = RunMoves.FAILED, outcome = CANCELLED, endedAt = at)
        } else {
            visit
        }
    }
    return run.copy(status = RunStatus.CANCELLED, currentNodeId = null, visits = visits, endedAt = at)
}

// How often a cancel reads the run again when the engine changed it meanwhile; who cancels; the runs already ended and
// the visits still under way. Top-level, not in a private companion, whose lines Kover counts as partly missed.
private const val ATTEMPTS = 3
private const val CANCELLED = "cancelled"
private val CANCELLERS = setOf(OrgRole.OWNER, OrgRole.ADMIN)
private val ENDED = setOf(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED)
private val UNDER_WAY = setOf(RunMoves.RUNNING, RunMoves.WAITING)
