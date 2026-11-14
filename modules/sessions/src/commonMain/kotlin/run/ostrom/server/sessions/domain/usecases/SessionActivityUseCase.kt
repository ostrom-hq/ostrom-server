// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.domain.usecases

import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.sessions.domain.models.ActivityChange
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.models.SessionAccess
import run.ostrom.server.sessions.domain.models.SessionActivityState
import run.ostrom.server.sessions.domain.models.SessionEndedException
import run.ostrom.server.sessions.domain.models.SessionNotFoundException
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Records whether a session's agent works or waits for a person, from its hooks (`sessions.activity.v1`). */
interface ReportSessionActivityUseCase {
    /**
     * Applies one report.
     *
     * @param actor The caller: the session's person, or an agent token of its launch.
     * @param id The segment, open.
     * @param state The reported state.
     * @param observedAt When the hook fired, by the reporter's clock; null keeps the stored observation.
     * @return The stored activity.
     */
    suspend operator fun invoke(
        actor: GateActor,
        id: String,
        state: SessionActivityState,
        observedAt: Instant?,
    ): ActivityChange
}

/**
 * [ReportSessionActivityUseCase] on the repository, TeamCode's `AgentRunServiceImpl.activity`: under the segment's
 * lock, so replicas apply a segment's reports one at a time. A report observed before the stored one changes nothing;
 * a repeated state keeps its time, except a `working` reported during a silence of the launcher and older than it,
 * which the sign of life that ends the silence would clear ([SessionActivityRules.silentSince]).
 *
 * @param repository The sessions.
 * @param clock The time.
 */
class ReportSessionActivityUseCaseImpl(
    private val repository: SessionsRepository,
    private val clock: Clock,
) : ReportSessionActivityUseCase {
    override suspend fun invoke(
        actor: GateActor,
        id: String,
        state: SessionActivityState,
        observedAt: Instant?,
    ): ActivityChange = repository.transaction(actor.orgId) {
        val session = lock(id) ?: throw SessionNotFoundException()
        SessionAccess.ensureOwner(actor, session)
        if (session.endedAt != null) throw SessionEndedException(session)
        val now = clock.now()
        // Bounded by the server's clock: a reporter clock far ahead cannot freeze the state once corrected.
        val observed = observedAt?.coerceAtMost(now + SessionActivityRules.CLOCK_SKEW)
        val stored = session.activityObservedAt
        if (observed != null && stored != null && observed < stored) {
            return@transaction ActivityChange(id, session.activity, session.activityAt, superseded = true)
        }
        val kept = keptSince(session, state, now)
        if (kept != null && observed == null) return@transaction ActivityChange(id, state, kept)
        val since = kept ?: now
        update(session.copy(activity = state, activityAt = since, activityObservedAt = observed ?: stored))
        ActivityChange(id, state, since)
    }

    // The time a repeated state keeps, or null for a new one; a `working` older than the silence it was reported
    // before starts again, as the sign of life that ends the silence would clear it.
    private fun keptSince(session: Session, state: SessionActivityState, now: Instant): Instant? {
        val since = session.activityAt?.takeIf { session.activity == state } ?: return null
        val silence = SessionActivityRules.silentSince(session, now)
        val stale = state == SessionActivityState.WORKING && silence != null && since <= silence
        return since.takeUnless { stale }
    }
}

/** The rules the reports and the signs of life share. */
object SessionActivityRules {
    /** How far ahead of the server a reporter's clock is believed. */
    val CLOCK_SKEW: Duration = 5.minutes

    /**
     * How long the launcher may stay without a sign of life (every 20 s) before it counts as silent: three missed,
     * as TeamCode's 90 s for its 30 s heartbeats; below the sweep's 120 s, which ends the segment.
     */
    val SILENCE: Duration = 60.seconds

    /**
     * When the current silence of [session]'s launcher began, or null when it is not silent (or never gave a sign of
     * life: a session without a launcher has no silence).
     */
    fun silentSince(session: Session, now: Instant): Instant? {
        val began = (session.lastSeenAt ?: return null) + SILENCE
        return began.takeIf { now > it }
    }

    /**
     * [session] after a sign of life at [now]: a `working` reported before the silence it ends is cleared, a state
     * reported during it is current, and `waiting_for_human` is kept, since a person still has to answer.
     */
    fun afterSignOfLife(session: Session, now: Instant): Session {
        val seen = session.copy(lastSeenAt = now)
        val silence = silentSince(session, now) ?: return seen
        val working = session.activityAt?.takeIf { session.activity == SessionActivityState.WORKING }
        return if (working != null && working <= silence) seen.copy(activity = null, activityAt = null) else seen
    }
}
