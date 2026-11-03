// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.engine

import dev.ostrom.core.api.models.NodeVisit
import dev.ostrom.core.api.models.RunStatus
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowNodeKind
import dev.ostrom.core.api.models.WorkflowRun
import dev.ostrom.server.workflowruns.domain.models.RunChange
import dev.ostrom.server.workflowruns.domain.models.RunRef
import dev.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import dev.ostrom.server.workflowruns.domain.services.AgentRequest
import dev.ostrom.server.workflowruns.domain.services.AgentState
import dev.ostrom.server.workflowruns.domain.services.GateRequest
import dev.ostrom.server.workflowruns.domain.services.GateState
import dev.ostrom.server.workflowruns.domain.services.RunAgents
import dev.ostrom.server.workflowruns.domain.services.RunGates
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Plays a leased run as far as it goes without waiting: one transaction per change. */
interface PlayRunUseCase {
    /**
     * Plays the run: starts it, begins and finishes visits, follows edges, until it waits for an agent or a person,
     * or ends. Each change is written only if the run is still as read, so a cancel is never overwritten and a visit
     * never begun twice; an effect (a session, a gate) is keyed by its visit, so asking it again finds the first one.
     *
     * @param ref The run.
     * @param owner The replica that holds its lease.
     *
     * @return When to look at it again, or null when nothing is left to play.
     */
    suspend fun play(ref: RunRef, owner: String): Instant?
}

/**
 * [PlayRunUseCase] on the repository, the agent sessions and the gates.
 *
 * @param repository The runs.
 * @param agents The agent sessions.
 * @param gates The gates.
 * @param clock The clock.
 * @param poll How long a run waiting for an agent or a person waits before its next look.
 */
class PlayRunUseCaseImpl(
    private val repository: RunEngineRepository,
    private val agents: RunAgents,
    private val gates: RunGates,
    private val clock: Clock,
    private val poll: Duration = POLL,
) : PlayRunUseCase {
    override suspend fun play(ref: RunRef, owner: String): Instant? {
        repeat(MAX_MOVES) {
            when (val step = step(ref, owner)) {
                Step.Again -> Unit
                is Step.Later -> return step.at
            }
        }
        return clock.now()
    }

    private suspend fun step(ref: RunRef, owner: String): Step {
        val played = repository.played(ref.orgId, ref.id) ?: return Step.Later(null)
        val run = played.run
        val moves = RunMoves(owner, clock.now())
        val last = run.visits.lastOrNull()
        return when {
            run.status in FINAL -> settle(ref, run)
            last == null -> write(ref, moves.start(run, played.graph))
            last.status in OPEN -> {
                val node = played.graph.nodes.first { it.id == last.nodeId }
                effect(Visit(ref, moves, run, last, node))
            }
            else -> write(ref, moves.follow(run, played.graph))
        }
    }

    // A cancelled run's agent session is ended by the engine, whichever replica sees the cancel first.
    private suspend fun settle(ref: RunRef, run: WorkflowRun): Step {
        val last = run.visits.lastOrNull()
        if (run.status == RunStatus.CANCELLED && last != null && last.kind == WorkflowNodeKind.AGENT) {
            agents.stop(ref.orgId, sessionOf(run.id, last.seq))
        }
        return Step.Later(null)
    }

    private suspend fun effect(visit: Visit): Step = when (visit.node.kind) {
        WorkflowNodeKind.AGENT -> agent(visit)
        WorkflowNodeKind.GATE ->
            visit.last.gateId.let { id -> if (id == null) open(visit) else decided(visit, Uuid.parse(id)) }
        WorkflowNodeKind.TRIGGER -> write(visit.ref, visit.moves.finish(visit.run, RunMoves.NEXT))
        else -> write(
            visit.ref,
            visit.moves.finish(
                visit.run,
                RunMoves.FAILED,
                failed = true,
                output = "The server does not play ${visit.node.kind.value} nodes yet.",
            ),
        )
    }

    // Conditions rather than a `when` on the nullable state, whose unreachable default Kover counts as a branch.
    private suspend fun agent(visit: Visit): Step {
        val sessionId = sessionOf(visit.run.id, visit.last.seq)
        val state = agents.state(visit.ref.orgId, sessionId)
        return when {
            state == null -> {
                val refusal = agents.start(visit.ref.orgId, AgentRequests.of(visit.run, visit.node, sessionId))
                if (refusal == null) later() else write(visit.ref, visit.failed(refusal))
            }
            state == AgentState.WORKING -> later()
            state == AgentState.FAILED -> write(visit.ref, visit.failed("The agent session failed to start."))
            else -> write(visit.ref, visit.moves.finish(visit.run, RunMoves.NEXT))
        }
    }

    private suspend fun open(visit: Visit): Step {
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
        val change = if (gateId == null) visit.failed("${opening.refusal}") else visit.moves.waiting(run, gateId)
        return write(visit.ref, change)
    }

    private suspend fun decided(visit: Visit, gateId: Uuid): Step {
        val state = gates.state(visit.ref.orgId, gateId)
        return when {
            state == null -> write(visit.ref, visit.failed("The gate is gone."))
            state == GateState.PENDING -> later()
            state == GateState.APPROVED -> write(visit.ref, visit.moves.finish(visit.run, RunMoves.APPROVED))
            else -> write(visit.ref, visit.moves.finish(visit.run, RunMoves.REJECTED))
        }
    }

    private fun later(): Step = Step.Later(clock.now() + poll)

    // A change refused (the run moved meanwhile: a cancel) is read again at once rather than dropped.
    private suspend fun write(ref: RunRef, change: RunChange): Step =
        if (repository.write(ref.orgId, change)) Step.Again else Step.Later(clock.now())

    private fun Visit.failed(output: String): RunChange = moves.finish(run, RunMoves.FAILED, failed = true, output)

    // The visit under way and what playing it needs.
    private class Visit(
        val ref: RunRef,
        val moves: RunMoves,
        val run: WorkflowRun,
        val last: NodeVisit,
        val node: WorkflowNode,
    )

    private sealed interface Step {
        data object Again : Step

        data class Later(val at: Instant?) : Step
    }

    /** The bounds of a play. */
    companion object {
        /** How long a run waiting for an agent or a person waits before its next look. */
        val POLL: Duration = 3.seconds

        /** The most changes one play makes before the run is due again: a lease is never held for long. */
        const val MAX_MOVES: Int = 20
    }
}

// Top-level, not in the companion: a private companion property has a getter nothing calls.
private val FINAL = setOf(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED)
private val OPEN = setOf(RunMoves.RUNNING, RunMoves.WAITING)

/** The cloud session an agent node asks for. */
object AgentRequests {
    /** The longest first message of a session (the runners module's bound). */
    const val MAX_PROMPT: Int = 20_000

    /** The longest label of a session (the runners module's bound). */
    const val MAX_NAME: Int = 120

    /** The agent a node that names none runs. */
    const val DEFAULT_AGENT: String = "claude"

    /**
     * The session of a visit: the node's instructions (else its detail, else its label), with the run's inputs in
     * place of `{{inputs.<id>}}`, run by the node's agent (Claude Code when it names none) and model.
     *
     * @param run The run.
     * @param node The agent node.
     * @param sessionId The session's id.
     *
     * @return The request.
     */
    fun of(run: WorkflowRun, node: WorkflowNode, sessionId: Uuid): AgentRequest {
        val spec = node.run
        val text = spec?.instructions ?: node.detail ?: node.label
        val prompt = run.inputs.entries.fold(text) { prompt, (id, value) -> prompt.replace("{{inputs.$id}}", value) }
        return AgentRequest(
            sessionId = sessionId,
            starter = run.startedBy,
            name = "${run.workflowName} · ${node.label}".take(MAX_NAME),
            agent = spec?.agent ?: DEFAULT_AGENT,
            model = spec?.model,
            prompt = prompt.take(MAX_PROMPT),
        )
    }
}
