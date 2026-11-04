// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.engine

import dev.ostrom.core.api.models.RunStatus
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowRun
import dev.ostrom.server.workflowruns.domain.models.RunChange
import dev.ostrom.server.workflowruns.domain.models.RunRef
import dev.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import dev.ostrom.server.workflowruns.domain.services.AgentRequest
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
 * [PlayRunUseCase] on the repository and the players of the node kinds.
 *
 * @param repository The runs.
 * @param players The players of the node kinds the server plays.
 * @param clock The clock.
 * @param poll How long a run waiting for an agent, a person or a child run waits before its next look.
 */
class PlayRunUseCaseImpl(
    private val repository: RunEngineRepository,
    private val players: NodePlayers,
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

    // What a cancelled run's last visit started (a session, a wait, a child run) is ended by the engine, whichever
    // replica sees the cancel first.
    private suspend fun settle(ref: RunRef, run: WorkflowRun): Step {
        val last = run.visits.lastOrNull()
        if (run.status == RunStatus.CANCELLED && last != null) players.of(last.kind)?.stop(ref.orgId, run, last)
        return Step.Later(null)
    }

    private suspend fun effect(visit: Visit): Step {
        val player = players.of(visit.node.kind)
        val change = if (player == null) {
            visit.failed("The server does not play ${visit.node.kind.value} nodes yet.")
        } else {
            player.play(visit)
        }
        return if (change == null) Step.Later(clock.now() + poll) else write(visit.ref, change)
    }

    // A change refused (the run moved meanwhile: a cancel) is read again at once rather than dropped.
    private suspend fun write(ref: RunRef, change: RunChange): Step =
        if (repository.write(ref.orgId, change)) Step.Again else Step.Later(clock.now())

    private sealed interface Step {
        data object Again : Step

        data class Later(val at: Instant?) : Step
    }

    /** The bounds of a play. */
    companion object {
        /** How long a run waiting for an agent, a person or a child run waits before its next look. */
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
