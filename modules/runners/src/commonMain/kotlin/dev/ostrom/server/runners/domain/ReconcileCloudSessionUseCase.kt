// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One reconciliation step of a session: compares its state with its desired state and the runtime, acts on the
 * runtime, then records the result. Cluster calls are made outside any database transaction; the record is a
 * compare-and-set, so a step that lost a race to an API write or a heartbeat is simply taken again.
 */
interface ReconcileCloudSessionUseCase {
    /**
     * Reconciles one session.
     *
     * @param session The session, as claimed.
     *
     * @return When to look at it again, or null when nothing is left to do until someone changes it.
     */
    suspend operator fun invoke(session: CloudSession): Instant?
}

/**
 * [ReconcileCloudSessionUseCase] on the repository, the runtime and the plan.
 *
 * Timings, as in TeamCode's cloud sessions: a start must report ready within [START_TIMEOUT]; a runner silent for
 * [HEARTBEAT_LOSS] is lost; a pause drains for at most [PAUSE_DEADLINE].
 *
 * @param repository The sessions.
 * @param driver The runtime.
 * @param tokens Mints the runner tokens.
 * @param plans The organizations' plans, for their hours.
 * @param usage The hours used.
 * @param clock The time.
 */
// Each step is a decision table read top to bottom: an early return per outcome reads better than nested branches,
// and one small function per state and per check keeps each table short.
@Suppress("ReturnCount", "TooManyFunctions")
class ReconcileCloudSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val driver: RuntimeDriver,
    private val tokens: RunnerTokens,
    private val plans: CloudPlanProvider,
    private val usage: CloudUsageMeter,
    private val clock: Clock,
) : ReconcileCloudSessionUseCase {
    override suspend fun invoke(session: CloudSession): Instant? {
        val now = clock.now()
        return when {
            session.state == SessionState.TERMINATED -> null
            session.desiredState == DesiredState.TERMINATED -> terminate(session, now)
            session.state == SessionState.FAILED -> null
            session.state == SessionState.CREATING || session.state == SessionState.RESUMING -> starting(session, now)
            session.state == SessionState.RUNNING -> running(session, now)
            session.state == SessionState.PAUSING -> pausing(session, now)
            else -> paused(session, now)
        }
    }

    private suspend fun terminate(session: CloudSession, now: Instant): Instant? {
        driver.stopExecutions(session)
        if (!driver.deleteWorkspace(session)) return now + STEP_DELAY
        val terminated = session.moveTo(SessionState.TERMINATED, now)
            .copy(terminatedAt = now, runnerTokenHash = null, phase = "cleaned")
        // A lost write (the runner's last heartbeat, sent while its container stopped) asks for a new look.
        return write(terminated, null)
    }

    private suspend fun starting(session: CloudSession, now: Instant): Instant? {
        if (session.desiredState == DesiredState.PAUSED) {
            driver.stopExecutions(session)
            return write(session.toPaused(now, session.failure), null)
        }
        val startedAt = session.executionStartedAt ?: return startExecution(session, now)
        val status = driver.executionStatus(session)
        val failed = status == ExecutionStatus.FAILED || status == ExecutionStatus.ENDED ||
            (status == ExecutionStatus.MISSING && now - startedAt > MISSING_GRACE) || now - startedAt > START_TIMEOUT
        if (!failed) return now + STEP_DELAY
        driver.stopExecutions(session)
        val failure = SessionFailure(
            "start_failed",
            "The session's runner did not start. Try again, or check the image.",
        )
        if (session.state == SessionState.RESUMING) return write(session.toPaused(now, failure), null)
        if (!driver.deleteWorkspace(session)) return now + STEP_DELAY
        val ended = session.moveTo(SessionState.FAILED, now)
            .copy(failure = failure, runnerTokenHash = null, executionStartedAt = null)
        return write(ended, null)
    }

    private suspend fun startExecution(session: CloudSession, now: Instant): Instant? {
        val refused = planFailure(session, now)
        if (refused != null) {
            driver.stopExecutions(session)
            return write(session.toPaused(now, refused).copy(desiredState = DesiredState.PAUSED), null)
        }
        driver.ensureWorkspace(session)
        val token = tokens.mint()
        val next = session.copy(
            epoch = session.epoch + 1,
            executionStartedAt = now,
            readyAt = null,
            lastHeartbeatAt = null,
            runnerTokenHash = tokens.hash(token),
            runnerStoppedAt = null,
            pausingSince = null,
            phase = "starting_runner",
            updatedAt = now,
        )
        // The row commits first: an execution the runtime runs always has its row, never the reverse.
        val stored = repository.compareAndSet(next, now + STEP_DELAY) ?: return now
        driver.startExecution(
            ExecutionSpec(
                stored.id,
                stored.orgId,
                stored.epoch,
                stored.agent,
                stored.repository,
                token,
                stored.limits,
                stored.model,
                stored.prompt.takeIf { stored.epoch == 1L },
            ),
        )
        return now + STEP_DELAY
    }

    private suspend fun running(session: CloudSession, now: Instant): Instant? {
        if (executionLost(session, now)) {
            driver.stopExecutions(session)
            val failure = SessionFailure(
                "execution_lost",
                "The session's runner stopped answering; the workspace was kept.",
            )
            return write(session.toPaused(now, failure).copy(desiredState = DesiredState.PAUSED), null)
        }
        if (session.desiredState != DesiredState.RUNNING) {
            return write(session.toPausing(now, session.failure), now + STEP_DELAY)
        }
        val reason = limitReached(session, now) ?: planFailure(session, now) ?: return now + WATCH_DELAY
        return write(session.toPausing(now, reason).copy(desiredState = DesiredState.PAUSED), now + STEP_DELAY)
    }

    private suspend fun executionLost(session: CloudSession, now: Instant): Boolean {
        val lastSign = session.lastHeartbeatAt ?: session.readyAt ?: now
        return now - lastSign > HEARTBEAT_LOSS || driver.executionStatus(session) in LOST_STATUSES
    }

    private fun limitReached(session: CloudSession, now: Instant): SessionFailure? {
        val idleSince = session.lastActivityAt ?: session.readyAt ?: now
        return when {
            now - idleSince >= session.limits.idleTimeoutMinutes.minutes ->
                SessionFailure(
                    "idle_timeout",
                    "Paused after ${session.limits.idleTimeoutMinutes} minutes without a request to the model.",
                )
            now - (session.readyAt ?: now) >= session.limits.maxRunMinutes.minutes ->
                SessionFailure("max_duration", "Paused after ${session.limits.maxRunMinutes} minutes of running.")
            else -> null
        }
    }

    private suspend fun pausing(session: CloudSession, now: Instant): Instant? {
        val since = session.pausingSince ?: now
        val lastSign = session.lastHeartbeatAt ?: since
        val done = session.runnerStoppedAt != null || now - since > PAUSE_DEADLINE || now - lastSign > HEARTBEAT_LOSS
        if (!done) return now + STEP_DELAY
        driver.stopExecutions(session)
        val paused = session.toPaused(now, session.failure)
        return write(paused, if (paused.desiredState == DesiredState.RUNNING) now else null)
    }

    private suspend fun paused(session: CloudSession, now: Instant): Instant? {
        if (session.desiredState != DesiredState.RUNNING) return null
        return write(
            session.moveTo(SessionState.RESUMING, now)
                .copy(phase = "scheduling", executionStartedAt = null, failure = null),
            now,
        )
    }

    /** Why the organization's plan stops the session now: cloud sessions closed to it, or its hours used up. */
    private suspend fun planFailure(session: CloudSession, now: Instant): SessionFailure? {
        val plan = plans.planFor(session.orgId)
        val hours = plan.monthlyHours?.let { it * SECONDS_PER_HOUR }
        return when {
            !plan.available -> SessionFailure("closed", "Cloud sessions are closed to this organization.")
            hours != null && usage.usedSeconds(session.orgId, now) >= hours ->
                SessionFailure("hours_exhausted", "The cloud hours of this period are used up.")
            else -> null
        }
    }

    /** Writes a step; a lost race asks for an immediate new look, which re-reads the row. */
    private suspend fun write(next: CloudSession, nextReconcileAt: Instant?): Instant? =
        if (repository.compareAndSet(next, nextReconcileAt) == null) clock.now() else nextReconcileAt

    private fun CloudSession.moveTo(state: SessionState, now: Instant): CloudSession {
        SessionStateMachine.requireTransition(this.state, state)
        return copy(state = state, updatedAt = now)
    }

    private fun CloudSession.toPausing(now: Instant, reason: SessionFailure?): CloudSession =
        moveTo(SessionState.PAUSING, now)
            .copy(pausingSince = now, runnerStoppedAt = null, phase = "draining", failure = reason)

    private fun CloudSession.toPaused(now: Instant, reason: SessionFailure?): CloudSession =
        moveTo(SessionState.PAUSED, now).copy(
            pausedAt = now,
            executionStartedAt = null,
            runnerTokenHash = null,
            runnerStoppedAt = null,
            pausingSince = null,
            readyAt = null,
            phase = null,
            failure = reason,
        )

    /** Constants and readers of the enclosing type. */
    companion object {
        /** A start must report ready within this time, image pull and repository clone included. */
        private val START_TIMEOUT: Duration = 15.minutes

        /** A runner silent for this long is lost; the runners list shows it offline after the same time. */
        internal val HEARTBEAT_LOSS: Duration = 90.seconds

        /** What the runtime reports for an execution that is no longer there. */
        private val LOST_STATUSES = setOf(ExecutionStatus.ENDED, ExecutionStatus.FAILED, ExecutionStatus.MISSING)

        /** A pause drains the agent for at most this long. */
        private val PAUSE_DEADLINE: Duration = 2.minutes

        /** A started execution the runtime does not list yet is given this long before it counts as lost. */
        private val MISSING_GRACE: Duration = 60.seconds

        /** The delay between two steps of a move in progress. */
        private val STEP_DELAY: Duration = 2.seconds

        /** The delay between two checks of a running session. */
        private val WATCH_DELAY: Duration = 15.seconds
    }
}
