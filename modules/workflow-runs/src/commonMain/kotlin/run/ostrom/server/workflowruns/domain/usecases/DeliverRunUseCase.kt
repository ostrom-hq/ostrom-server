// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.workflowruns.domain.engine.RunMoves
import run.ostrom.server.workflowruns.domain.engine.sessionOf
import run.ostrom.server.workflowruns.domain.models.Deliverables
import run.ostrom.server.workflowruns.domain.models.InvalidDeliverableException
import run.ostrom.server.workflowruns.domain.models.NothingToDeliverException
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunNotFoundException
import run.ostrom.server.workflowruns.domain.models.TodoOpenException
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.services.RunAgents
import run.ostrom.server.workflowruns.domain.services.RunTodos
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Hands in what a run's current agent node asks for (contract `deliverRun`). */
interface DeliverRunUseCase {
    /**
     * Delivers into the run: the output is checked against the node's deliverable, then the visit ends `delivered`
     * with it, in one change written only if the run is still as read, so of two deliveries one wins.
     *
     * @param actor The caller: the run's starter, with a person's credential.
     * @param id The run.
     * @param output What is delivered.
     * @param person The person's id, for the audit log.
     *
     * @return The run after the delivery.
     *
     * @throws RunNotFoundException When the organization has no such run, or it is not the caller's.
     * @throws NothingToDeliverException When its current node waits for no delivery (none asked, its session not
     *   started yet, the run ended), or another delivery came first.
     * @throws TodoOpenException When the step's session still has open todo items.
     * @throws InvalidDeliverableException When the output is not what the node asks for: nothing is stored.
     */
    suspend operator fun invoke(actor: GateActor, id: String, output: JsonElement, person: Uuid?): WorkflowRun
}

/**
 * [DeliverRunUseCase] on the engine's repository (TeamCode `WorkflowRunService.deliver`).
 *
 * @param engine The runs, to read one with its graph and change it.
 * @param agents The agent sessions: a step delivers once its session exists.
 * @param todos The sessions' todos, when a module keeps them.
 * @param clock The clock.
 */
class DeliverRunUseCaseImpl(
    private val engine: RunEngineRepository,
    private val agents: RunAgents,
    private val todos: RunTodos?,
    private val clock: Clock,
) : DeliverRunUseCase {
    override suspend fun invoke(actor: GateActor, id: String, output: JsonElement, person: Uuid?): WorkflowRun {
        val starter = actor.id.takeIf { actor.kind == ActorKind.USER } ?: throw RunNotFoundException()
        val played = engine.played(actor.orgId, id)?.takeIf { it.run.startedBy == starter }
            ?: throw RunNotFoundException()
        val run = played.run
        val visit = awaited(run) ?: throw NothingToDeliverException()
        val deliverable = played.graph.nodes.firstOrNull { it.id == visit.nodeId }?.run?.deliverable
            ?: throw NothingToDeliverException()
        val session = sessionOf(run.id, visit.seq)
        if (agents.state(actor.orgId, session) == null) throw NothingToDeliverException()
        todos?.refusal(actor.orgId, session.toString())?.let { throw TodoOpenException(it) }
        val text = Deliverables.validate(deliverable, output)
        val now = clock.now()
        val next = RunMoves(DELIVERY, now).finish(run, RunMoves.DELIVERED, output = text).next
        val audit = RunAudit("delivered", auditDetails("node" to visit.nodeId, "seq" to visit.seq), person)
        // One change on the run as read: a second delivery, or the engine failing the visit meanwhile, finds it changed.
        if (!engine.write(actor.orgId, RunChange(run, next, null, audit = listOf(audit), dueAt = now))) {
            throw NothingToDeliverException()
        }
        return next
    }

    // The run's visit under way, when it is an agent's.
    private fun awaited(run: WorkflowRun): NodeVisit? = run.visits.lastOrNull()?.takeIf {
        run.status !in ENDED && it.status == RunMoves.RUNNING && it.kind == WorkflowNodeKind.AGENT
    }
}

// The name a delivery's change is made under (no engine replica's lease), and the runs already ended.
private const val DELIVERY = "delivery"
private val ENDED = setOf(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED)
