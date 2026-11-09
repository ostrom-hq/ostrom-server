// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.engine

import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.compiler.domain.services.NodeConditions
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunRef
import run.ostrom.server.workflowruns.domain.services.AgentState
import run.ostrom.server.workflowruns.domain.services.ChildOpening
import run.ostrom.server.workflowruns.domain.services.ChildRequest
import run.ostrom.server.workflowruns.domain.services.ChildResult
import run.ostrom.server.workflowruns.domain.services.GateRequest
import run.ostrom.server.workflowruns.domain.services.GateState
import run.ostrom.server.workflowruns.domain.services.RunAgents
import run.ostrom.server.workflowruns.domain.services.RunChildren
import run.ostrom.server.workflowruns.domain.services.RunGates
import run.ostrom.server.workflowruns.domain.services.RunWaits
import run.ostrom.server.workflowruns.domain.services.WaitOpening
import run.ostrom.server.workflowruns.domain.services.WaitRequest
import run.ostrom.server.workflowruns.domain.services.WaitResult
import run.ostrom.server.workflowruns.domain.services.WaitState
import kotlin.uuid.Uuid

/**
 * The visit under way and what playing it needs.
 *
 * @property ref The run.
 * @property moves The changes the engine makes, as the replica that holds the lease.
 * @property run The run as read.
 * @property last Its visit under way.
 * @property node The node visited.
 */
class Visit(
    val ref: RunRef,
    val moves: RunMoves,
    val run: WorkflowRun,
    val last: NodeVisit,
    val node: WorkflowNode,
) {
    /**
     * Fails the visit.
     *
     * @param output Why.
     *
     * @return The change.
     */
    fun failed(output: String): RunChange = moves.finish(run, RunMoves.FAILED, failed = true, output)

    /**
     * Finishes the visit with an outcome.
     *
     * @param outcome The outcome, which chooses the next edge.
     * @param output What the node said.
     *
     * @return The change.
     */
    fun done(outcome: String, output: String? = null): RunChange = moves.finish(run, outcome, output = output)

    /**
     * Marks the visit as holding what it started (a gate, a wait, a child run): the engine looks at it again later.
     *
     * @param held The visit, with what it holds.
     * @param event The audit event.
     * @param detail The audit fact that names what it holds.
     *
     * @return The change.
     */
    fun held(held: NodeVisit, event: String, detail: Pair<String, Any?>): RunChange =
        moves.held(run, held, event, detail)
}

/** Plays the visits of one kind of node. */
interface NodePlayer {
    /**
     * Plays the visit as far as it goes without waiting. Whatever it starts is keyed by the visit, so a visit played
     * again after a restart finds what the first play started.
     *
     * @param visit The visit.
     *
     * @return The change, or null when the visit waits for something before the engine's next look.
     */
    suspend fun play(visit: Visit): RunChange?

    /**
     * Ends what the visit of a cancelled run started.
     *
     * @param orgId The organization.
     * @param run The run, cancelled.
     * @param visit Its last visit.
     */
    suspend fun stop(orgId: Uuid, run: WorkflowRun, visit: NodeVisit)
}

/**
 * The players of the node kinds the server plays: `trigger`, `agent`, `gate`, `wait`, `workflow` and `check`.
 *
 * @param agents The agent sessions.
 * @param gates The gates.
 * @param waits The waits.
 * @param children The child runs.
 */
class NodePlayers(agents: RunAgents, gates: RunGates, waits: RunWaits, children: RunChildren) {
    private val players = mapOf(
        WorkflowNodeKind.TRIGGER to TriggerPlayer,
        WorkflowNodeKind.AGENT to AgentPlayer(agents),
        WorkflowNodeKind.GATE to GatePlayer(gates),
        WorkflowNodeKind.WAIT to WaitPlayer(waits),
        WorkflowNodeKind.WORKFLOW to ChildPlayer(children),
        WorkflowNodeKind.CHECK to CheckPlayer,
    )

    /**
     * The player of a node kind.
     *
     * @param kind The kind.
     *
     * @return Its player, or null when the server does not play it.
     */
    fun of(kind: WorkflowNodeKind): NodePlayer? = players[kind]
}

/** A trigger has done its part once visited. */
object TriggerPlayer : NodePlayer {
    override suspend fun play(visit: Visit): RunChange = visit.done(RunMoves.NEXT)

    // A trigger starts nothing.
    override suspend fun stop(orgId: Uuid, run: WorkflowRun, visit: NodeVisit) = Unit
}

/**
 * A check node: its condition, read on the visits before its own, ends the visit `true` or `false`; a condition that
 * does not evaluate fails the visit, with the reason, and never chooses a branch.
 */
object CheckPlayer : NodePlayer {
    override suspend fun play(visit: Visit): RunChange {
        val verdict = NodeConditions.evaluate(visit.node.condition, visit.run.visits.dropLast(1))
        val holds = verdict.holds
        return if (holds == null) visit.failed("${verdict.reason}") else visit.done(holds.toString())
    }

    // A check starts nothing.
    override suspend fun stop(orgId: Uuid, run: WorkflowRun, visit: NodeVisit) = Unit
}

/**
 * An agent node: a cloud session of the run's starter, keyed by the visit; the node is done once the session no longer
 * holds an execution.
 *
 * @param agents The agent sessions.
 */
class AgentPlayer(private val agents: RunAgents) : NodePlayer {
    // Conditions rather than a `when` on the nullable state, whose unreachable default Kover counts as a branch.
    override suspend fun play(visit: Visit): RunChange? {
        val sessionId = sessionOf(visit.run.id, visit.last.seq)
        val state = agents.state(visit.ref.orgId, sessionId)
        return when {
            state == null -> {
                val refusal = agents.start(visit.ref.orgId, AgentRequests.of(visit.run, visit.node, sessionId))
                if (refusal == null) null else visit.failed(refusal)
            }
            state == AgentState.WORKING -> null
            state == AgentState.FAILED -> visit.failed("The agent session failed to start.")
            else -> visit.done(RunMoves.NEXT)
        }
    }

    override suspend fun stop(orgId: Uuid, run: WorkflowRun, visit: NodeVisit) {
        agents.stop(orgId, sessionOf(run.id, visit.seq))
    }
}

/**
 * A gate node: an enforced gate of its approvers, asked for the run's starter; its outcome is `approved` or `rejected`.
 *
 * @param gates The gates.
 */
class GatePlayer(private val gates: RunGates) : NodePlayer {
    override suspend fun play(visit: Visit): RunChange? {
        val gateId = visit.last.gateId
        return if (gateId == null) open(visit) else decided(visit, gates.state(visit.ref.orgId, Uuid.parse(gateId)))
    }

    private suspend fun open(visit: Visit): RunChange {
        val run = visit.run
        val node = visit.node
        val request = GateRequest(
            runId = run.id,
            seq = visit.last.seq,
            workflowId = run.workflowId,
            workflowName = run.workflowName,
            nodeId = node.id,
            label = node.label,
            approvers = node.approvers.orEmpty(),
            asker = run.startedBy,
        )
        val opening = gates.open(visit.ref.orgId, request)
        val gateId = opening.gateId
        return if (gateId == null) {
            visit.failed("${opening.refusal}")
        } else {
            val held = visit.last.copy(status = RunMoves.WAITING, gateId = gateId.toString())
            visit.held(held, "gate_opened", "gate" to gateId)
        }
    }

    private fun decided(visit: Visit, state: GateState?): RunChange? = when {
        state == null -> visit.failed("The gate is gone.")
        state == GateState.PENDING -> null
        state == GateState.APPROVED -> visit.done(RunMoves.APPROVED)
        else -> visit.done(RunMoves.REJECTED)
    }

    // A gate of a cancelled run stays as it is: no run uses its yes any more.
    override suspend fun stop(orgId: Uuid, run: WorkflowRun, visit: NodeVisit) = Unit
}

/**
 * A wait node: its question, asked of its first approver (else its owner, else the run's starter) for the run's
 * starter, keyed by the visit. The run waits without holding anything: the wait is stored, and the engine looks at it
 * again until it is answered, also after a restart. The answer's action is the visit's outcome, its values its output.
 *
 * @param waits The waits.
 */
class WaitPlayer(private val waits: RunWaits) : NodePlayer {
    override suspend fun play(visit: Visit): RunChange? {
        val waitId = visit.last.waitId
        return if (waitId == null) ask(visit) else answered(visit, waits.result(visit.ref.orgId, Uuid.parse(waitId)))
    }

    private suspend fun ask(visit: Visit): RunChange {
        val node = visit.node
        val question = node.wait
        val assignee = node.approvers?.firstOrNull() ?: node.owner
        val opening = if (question == null) {
            WaitOpening(refusal = "The wait node asks no question.")
        } else {
            val request = WaitRequest(visit.run.id, visit.last.seq, visit.run.startedBy, assignee, question)
            waits.ask(visit.ref.orgId, request)
        }
        val waitId = opening.waitId
        return if (waitId == null) {
            visit.failed("${opening.refusal}")
        } else {
            val held = visit.last.copy(status = RunMoves.WAITING, waitId = waitId.toString())
            visit.held(held, "wait_asked", "wait" to waitId)
        }
    }

    private fun answered(visit: Visit, result: WaitResult?): RunChange? = when {
        result == null -> visit.failed("The wait is gone.")
        result.state == WaitState.OPEN -> null
        result.state == WaitState.CANCELLED -> visit.failed("The wait was cancelled.")
        else -> visit.done(result.action, result.values)
    }

    override suspend fun stop(orgId: Uuid, run: WorkflowRun, visit: NodeVisit) {
        visit.waitId?.let { waits.cancel(orgId, run.startedBy, Uuid.parse(it)) }
    }
}

/**
 * A workflow node: a child run of another workflow of the organization, with the node's arguments as its inputs,
 * keyed by the visit; the node is done when the child succeeds, and fails when the child fails or is cancelled.
 *
 * @param children The child runs.
 */
class ChildPlayer(private val children: RunChildren) : NodePlayer {
    override suspend fun play(visit: Visit): RunChange? {
        val childId = visit.last.childRunId
        return if (childId == null) start(visit) else ended(visit, children.result(visit.ref.orgId, childId))
    }

    private suspend fun start(visit: Visit): RunChange {
        val workflowId = visit.node.workflowId
        val opening = if (workflowId == null) {
            ChildOpening(refusal = "The workflow node names no workflow.")
        } else {
            val request = ChildRequest(visit.run, visit.last.seq, workflowId, visit.node.arguments.orEmpty())
            children.start(visit.ref.orgId, request)
        }
        val childId = opening.runId
        return if (childId == null) {
            visit.failed("${opening.refusal}")
        } else {
            visit.held(visit.last.copy(childRunId = childId), "child_started", "child" to childId)
        }
    }

    private fun ended(visit: Visit, child: ChildResult?): RunChange? = when {
        child == null -> visit.failed("The child run is gone.")
        child.status == RunStatus.SUCCEEDED -> visit.done(RunMoves.NEXT, child.output)
        child.status == RunStatus.FAILED -> visit.failed("The child run failed: ${child.failure}")
        child.status == RunStatus.CANCELLED -> visit.failed("The child run was cancelled.")
        else -> null
    }

    override suspend fun stop(orgId: Uuid, run: WorkflowRun, visit: NodeVisit) {
        visit.childRunId?.let { children.cancel(orgId, it) }
    }
}
