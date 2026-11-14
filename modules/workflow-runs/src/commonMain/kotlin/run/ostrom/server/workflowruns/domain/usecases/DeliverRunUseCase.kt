// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.Deliverable
import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowNode
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
import run.ostrom.server.workflowruns.domain.models.PlayedRun
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
 * [DeliverRunUseCase] on the deliveries (TeamCode `WorkflowRunService.deliver`).
 *
 * @param engine The runs, to read one with its graph.
 * @param deliveries Hands a delivery into a run.
 */
class DeliverRunUseCaseImpl(
    private val engine: RunEngineRepository,
    private val deliveries: RunDeliveries,
) : DeliverRunUseCase {
    override suspend fun invoke(actor: GateActor, id: String, output: JsonElement, person: Uuid?): WorkflowRun {
        val starter = actor.id.takeIf { actor.kind == ActorKind.USER } ?: throw RunNotFoundException()
        val played = engine.played(actor.orgId, id)?.takeIf { it.run.startedBy == starter }
            ?: throw RunNotFoundException()
        return deliveries.deliver(actor.orgId, played, output, person)
    }
}

/**
 * Hands a delivery into a run, for `deliverRun` and `deliverWorkflowSession`: the output is checked against the
 * deliverable of the agent node under way, then the visit ends `delivered` with it, in one change written only if the
 * run is still as read, so of two deliveries one wins.
 *
 * @param engine The runs, to change one.
 * @param agents The agent sessions: a step delivers once its session exists.
 * @param todos The sessions' todos, when a module keeps them.
 * @param clock The clock.
 */
class RunDeliveries(
    private val engine: RunEngineRepository,
    private val agents: RunAgents,
    private val todos: RunTodos?,
    private val clock: Clock,
) {
    /**
     * Delivers into the run.
     *
     * @param orgId The organization.
     * @param played The run, read with its graph.
     * @param output What is delivered.
     * @param person The person's id, for the audit log.
     * @param session The cloud session the delivery comes for: its visit must be the one under way; any when null.
     *
     * @return The run after the delivery.
     *
     * @throws NothingToDeliverException When its current node waits for no delivery (none asked, its session not
     *   started yet, the run ended), or another delivery came first.
     * @throws TodoOpenException When the step's session still has open todo items.
     * @throws InvalidDeliverableException When the output is not what the node asks for: nothing is stored.
     */
    suspend fun deliver(
        orgId: Uuid,
        played: PlayedRun,
        output: JsonElement,
        person: Uuid?,
        session: Uuid? = null,
    ): WorkflowRun {
        val run = played.run
        val (step, sessionId) = ready(orgId, played, session) ?: throw NothingToDeliverException()
        val (visit, _, deliverable) = step
        val text = Deliverables.validate(deliverable, output)
        val now = clock.now()
        val moves = RunMoves(DELIVERY, now)
        // A delivery that comes before the engine named the session names it: the step's visit always does.
        val named = if (run.visits.last().sessionId == null) moves.dispatched(run, sessionId).next else run
        val next = moves.finish(named, RunMoves.DELIVERED, output = text).next
        val audit = RunAudit("delivered", auditDetails("node" to visit.nodeId, "seq" to visit.seq), person)
        // One change on the run as read: a second delivery, or the engine failing the visit meanwhile, finds it
        // changed.
        if (!engine.write(orgId, RunChange(run, next, null, audit = listOf(audit), dueAt = now))) {
            throw NothingToDeliverException()
        }
        return next
    }

    // The step under way and its session, when it waits for this delivery: of the session named if any, its session
    // started. A session with open todo items is refused.
    private suspend fun ready(orgId: Uuid, played: PlayedRun, session: Uuid?): Pair<AwaitedStep, Uuid>? {
        val step = awaitedStep(played) ?: return null
        val sessionId = sessionOf(played.run.id, step.visit.seq)
        val started = (session == null || session == sessionId) && agents.state(orgId, sessionId) != null
        if (started) todos?.refusal(orgId, sessionId.toString())?.let { throw TodoOpenException(it) }
        return (step to sessionId).takeIf { started }
    }
}

/**
 * The agent step a run waits on a delivery for.
 *
 * @property visit Its visit under way.
 * @property node Its agent node.
 * @property deliverable What the node asks for.
 */
data class AwaitedStep(val visit: NodeVisit, val node: WorkflowNode, val deliverable: Deliverable)

/**
 * The agent step a run waits on a delivery for: its visit under way, of an agent node that asks for a deliverable.
 *
 * @param played The run, with its graph.
 *
 * @return The step, or null when the run waits for no delivery.
 */
fun awaitedStep(played: PlayedRun): AwaitedStep? {
    val run = played.run
    val visit = run.visits.lastOrNull()?.takeIf {
        run.status !in ENDED && it.status == RunMoves.RUNNING && it.kind == WorkflowNodeKind.AGENT
    } ?: return null
    val node = played.graph.nodes.firstOrNull { it.id == visit.nodeId }
    return node?.run?.deliverable?.let { AwaitedStep(visit, node, it) }
}

// The name a delivery's change is made under (no engine replica's lease), and the runs already ended.
private const val DELIVERY = "delivery"
private val ENDED = setOf(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED)
