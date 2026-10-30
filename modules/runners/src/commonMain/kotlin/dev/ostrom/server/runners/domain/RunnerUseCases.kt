// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A heartbeat of the runner of an execution, every [HEARTBEAT_INTERVAL].
 *
 * @property sessionId The session the runner was started for.
 * @property epoch The execution the runner was started for.
 * @property ready Whether the workspace is prepared and the agent started.
 * @property stopped Whether the agent was stopped after a stop instruction (the pause is drained).
 * @property lastModelRequestAt The agent's last request to the model, if the runner saw one.
 */
data class RunnerHeartbeat(
    val sessionId: Uuid,
    val epoch: Long,
    val ready: Boolean,
    val stopped: Boolean,
    val lastModelRequestAt: Instant?,
)

/** What the runner must do after a heartbeat. */
enum class RunnerInstruction(val wire: String) {
    /** Keep the agent running. */
    RUN("run"),

    /** Stop the agent gracefully, then report `stopped`; the pod is then removed. */
    STOP("stop"),
}

/**
 * The answer to a heartbeat.
 *
 * @property instruction What to do.
 * @property state The session's state, for the runner's logs.
 */
data class HeartbeatAnswer(
    val instruction: RunnerInstruction,
    val state: SessionState,
)

/** The runner channel: authenticates a runner by its token and records its heartbeats. */
interface RunnerHeartbeatUseCase {
    /**
     * Records a heartbeat.
     *
     * @param token The runner token, as presented.
     * @param heartbeat The heartbeat.
     *
     * @return What the runner must do.
     *
     * @throws RunnerUnauthenticatedException When the token is unknown.
     * @throws ExecutionFencedException When the token is not the one of the session and execution named.
     */
    suspend operator fun invoke(token: String, heartbeat: RunnerHeartbeat): HeartbeatAnswer
}

/**
 * [RunnerHeartbeatUseCase] on the repository.
 *
 * @param repository The sessions.
 * @param tokens Hashes the presented token.
 * @param usage Counts the execution time.
 * @param clock The time.
 */
class RunnerHeartbeatUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val tokens: RunnerTokens,
    private val usage: CloudUsageMeter,
    private val clock: Clock,
) : RunnerHeartbeatUseCase {
    // The three refusals are the runner protocol's answers (unknown token, fenced, lost every race).
    @Suppress("ThrowsCount")
    override suspend fun invoke(token: String, heartbeat: RunnerHeartbeat): HeartbeatAnswer {
        repeat(MAX_ATTEMPTS) {
            val session = repository.findByRunnerTokenHash(tokens.hash(token))
                ?: throw RunnerUnauthenticatedException()
            if (session.id != heartbeat.sessionId || session.epoch != heartbeat.epoch) throw ExecutionFencedException()
            val now = clock.now()
            val updated = apply(session, heartbeat, now)
            val changed = updated.state != session.state || heartbeat.stopped
            val stored = repository.compareAndSet(updated, if (changed) now else null)
            if (stored != null) {
                val last = session.lastHeartbeatAt ?: session.executionStartedAt ?: now
                val seconds = (now - last).inWholeSeconds.coerceIn(0, MAX_BILLED_GAP.inWholeSeconds)
                // The execution's first report counts its session once, even when no second has passed yet.
                val first = session.lastHeartbeatAt == null
                if (seconds > 0 || first) usage.record(session.orgId, seconds, first, now)
                return HeartbeatAnswer(instructionFor(stored), stored.state)
            }
        }
        // Every attempt lost a race with the reconciler: the runner retries at its next heartbeat.
        throw ExecutionFencedException()
    }

    private fun apply(session: CloudSession, heartbeat: RunnerHeartbeat, now: Instant): CloudSession {
        val modelActivity = heartbeat.lastModelRequestAt?.takeIf { it <= now }
        var next = session.copy(
            lastHeartbeatAt = now,
            lastActivityAt = maxOfNullable(session.lastActivityAt, modelActivity),
            updatedAt = now,
        )
        val starting = session.state == SessionState.CREATING || session.state == SessionState.RESUMING
        if (starting && heartbeat.ready) {
            SessionStateMachine.requireTransition(session.state, SessionState.RUNNING)
            next = next.copy(
                state = SessionState.RUNNING,
                phase = null,
                readyAt = now,
                lastActivityAt = now,
                failure = null,
            )
        }
        if (heartbeat.stopped && session.state == SessionState.PAUSING && session.runnerStoppedAt == null) {
            next = next.copy(runnerStoppedAt = now)
        }
        return next
    }

    private fun instructionFor(session: CloudSession): RunnerInstruction {
        val stopping = session.state == SessionState.PAUSING || session.desiredState != DesiredState.RUNNING
        return if (stopping) RunnerInstruction.STOP else RunnerInstruction.RUN
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        /** The longest gap between two heartbeats that counts as execution time; a longer one counts this much. */
        private val MAX_BILLED_GAP = 60.seconds

        private const val MAX_ATTEMPTS = 3
    }
}

/** Records activity on a session (terminal input, a request to the model through the gateway). */
interface RecordCloudSessionActivityUseCase {
    /**
     * Records activity now, so the session is not paused for inactivity.
     *
     * @param sessionId The session.
     */
    suspend operator fun invoke(sessionId: Uuid)
}

/**
 * [RecordCloudSessionActivityUseCase] on the repository.
 *
 * @param repository The sessions.
 * @param clock The time.
 */
class RecordCloudSessionActivityUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val clock: Clock,
) : RecordCloudSessionActivityUseCase {
    override suspend fun invoke(sessionId: Uuid) = repository.touchActivity(sessionId, clock.now())
}

internal fun maxOfNullable(a: Instant?, b: Instant?): Instant? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}
