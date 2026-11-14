// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.sessions.PendingWaitAnswers
import run.ostrom.server.kernel.sessions.SessionEndListener
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
 * [HEARTBEAT_LOSS] is lost; a pause drains for at most [PAUSE_DEADLINE]. A lost execution pauses its session, except on
 * a runtime that [relocates][RuntimeDriver.relocates]: there, it starts again on another machine.
 *
 * @param repository The sessions.
 * @param driver The runtime.
 * @param tokens Mints the runner tokens.
 * @param plans The organizations' plans, for their hours.
 * @param usage The hours used.
 * @param clock The time.
 * @param ended Told once a session is terminated, so that what its agent asked there ends with it (its open waits are
 *   cancelled): its agent asks in the cloud session itself.
 * @param profiles Whether a Kubernetes session's cluster still takes it, at each start.
 * @param answers Whether answers to the session's waits, given while its execution ran, were never handed over: the
 *   idle guard then starts the execution again instead of pausing it, so its runner hands them over (an answer to a
 *   running session resumes nothing, and nothing would resume it once paused).
 * @param retention How long a paused session keeps its workspace, read when it pauses ([CloudSession.expiresAt]).
 * @param backgroundWork How long the agent's background work holds off the idle pause, per organization.
 */
// Each step is a decision table read top to bottom: an early return per outcome reads better than nested branches,
// and one small function per state and per check keeps each table short.
// Its eight collaborators are the ports a step reads and writes, each named at its one call site.
@Suppress("ReturnCount", "TooManyFunctions", "LongParameterList")
class ReconcileCloudSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val driver: RuntimeDriver,
    private val tokens: RunnerTokens,
    private val plans: CloudPlanProvider,
    private val usage: CloudUsageMeter,
    private val clock: Clock,
    private val ended: List<SessionEndListener> = emptyList(),
    private val profiles: SessionProfiles = NoSessionProfiles,
    private val answers: PendingWaitAnswers = NoPendingWaitAnswers,
    private val retention: PausedRetention = KeepPausedSessions,
    private val backgroundWork: BackgroundWorkLimit,
) : ReconcileCloudSessionUseCase {
    override suspend fun invoke(session: CloudSession): Instant? {
        val now = clock.now()
        if (session.state == SessionState.TERMINATED) {
            return if (session.phase == CLEANUP_BLOCKED) blockedCleanup(session, now) else null
        }
        // A session of the former variables gets their cluster's profile first, whatever happened at the upgrade.
        if (session.profile == null) {
            profiles.legacy()?.let { return write(session.copy(profile = it, updatedAt = now), now) }
        }
        // Its cluster opens again: its retries are no longer counted.
        if (session.clusterUnreachableSince != null && driver.reachable(session)) {
            return write(session.copy(clusterUnreachableSince = null, updatedAt = now), now)
        }
        return try {
            step(session, now)
        } catch (_: RuntimeUnreachableException) {
            unreachable(session, now)
        }
    }

    private suspend fun step(session: CloudSession, now: Instant): Instant? =
        when {
            session.desiredState == DesiredState.TERMINATED -> terminate(session, now)
            session.state == SessionState.FAILED -> null
            session.preparationFailed -> preparationFailed(session, now)
            session.state == SessionState.CREATING || session.state == SessionState.RESUMING -> starting(session, now)
            session.state == SessionState.RUNNING -> running(session, now)
            session.state == SessionState.PAUSING -> pausing(session, now)
            else -> paused(session, now)
        }

    /**
     * A step found the session's cluster unreachable. It is tried again every [UNREACHABLE_RETRY] for
     * [UNREACHABLE_GRACE]; past that, the session no longer waits for its cluster, so it can always be stopped and
     * never holds its place under the running cap: TeamCode's lost execution (a first start fails, a resume or a
     * running session pauses and is not resumed by itself), and a deletion ends with its cleanup blocked
     * ([blockedCleanup]).
     */
    private suspend fun unreachable(session: CloudSession, now: Instant): Instant? {
        val since = session.clusterUnreachableSince
        if (since == null) {
            return write(session.copy(clusterUnreachableSince = now, updatedAt = now), now + UNREACHABLE_RETRY)
        }
        if (now - since < UNREACHABLE_GRACE) return now + UNREACHABLE_RETRY
        val outcome = when {
            session.desiredState == DesiredState.TERMINATED -> session.toTerminated(now).copy(
                executionStartedAt = null,
                phase = CLEANUP_BLOCKED,
                failure = CLUSTER_LOST_CLEANUP,
            )
            session.state == SessionState.CREATING -> session.moveTo(SessionState.FAILED, now)
                .copy(failure = CLUSTER_LOST, executionStartedAt = null)
            else -> session.toPaused(now, CLUSTER_LOST).copy(desiredState = DesiredState.PAUSED)
        }
        val terminated = outcome.state == SessionState.TERMINATED
        val next = write(
            outcome.copy(runnerTokenHash = null, clusterUnreachableSince = null),
            if (terminated) now + BLOCKED_RECHECK else outcome.expiresAt,
        )
        // Terminated, its cleanup still blocked: what its agent asked ends now (the listeners are idempotent).
        if (terminated) ended.forEach { it.ended(session.orgId, listOf(session.id.toString())) }
        return next
    }

    /**
     * A session deleted while its cluster was unreachable: its pods and workspace may remain there, so its cleanup is
     * tried again every [BLOCKED_RECHECK] (TeamCode's blocked deletion), until its cluster opens and they are gone.
     */
    private suspend fun blockedCleanup(session: CloudSession, now: Instant): Instant? {
        if (!driver.reachable(session)) return now + BLOCKED_RECHECK
        try {
            driver.stopExecutions(session)
            if (!driver.deleteWorkspace(session)) return now + STEP_DELAY
        } catch (_: RuntimeUnreachableException) {
            return now + BLOCKED_RECHECK
        }
        return write(session.copy(phase = "cleaned", failure = null, updatedAt = now), null)
    }

    private suspend fun terminate(session: CloudSession, now: Instant): Instant? {
        driver.stopExecutions(session)
        if (!driver.deleteWorkspace(session)) return now + STEP_DELAY
        val terminated = session.toTerminated(now).copy(runnerTokenHash = null, phase = "cleaned")
        // A lost write (the runner's last heartbeat, sent while its container stopped) asks for a new look.
        val next = write(terminated, null)
        // Once written: a lost write is looked at again, and its listeners told then (they are idempotent).
        if (next == null) ended.forEach { it.ended(session.orgId, listOf(session.id.toString())) }
        return next
    }

    private suspend fun starting(session: CloudSession, now: Instant): Instant? {
        if (session.desiredState == DesiredState.PAUSED) {
            driver.stopExecutions(session)
            return parked(session.toPaused(now, session.failure))
        }
        val startedAt = session.executionStartedAt ?: return startExecution(session, now)
        val status = driver.executionStatus(session)
        if (status == ExecutionStatus.HOST_LOST) {
            // Its machine died before it was ready: a new execution, on another machine.
            driver.stopExecutions(session)
            return write(session.copy(executionStartedAt = null, phase = "scheduling", updatedAt = now), now)
        }
        // Waiting for a machine with room, as long as it takes; its start is timed once one takes it.
        if (status == ExecutionStatus.QUEUED) {
            if (session.phase == QUEUED_PHASE) return now + WATCH_DELAY
            return write(session.copy(phase = QUEUED_PHASE, updatedAt = now), now + WATCH_DELAY)
        }
        if (session.phase == QUEUED_PHASE) {
            val taken = session.copy(phase = STARTING_PHASE, executionStartedAt = now, updatedAt = now)
            return write(taken, now + STEP_DELAY)
        }
        val failed = status == ExecutionStatus.FAILED || status == ExecutionStatus.ENDED ||
            (status == ExecutionStatus.MISSING && now - startedAt > MISSING_GRACE) || now - startedAt > START_TIMEOUT
        if (!failed) return now + STEP_DELAY
        driver.stopExecutions(session)
        val failure = SessionFailure(
            "start_failed",
            "The session's runner did not start. Try again, or check the image.",
        )
        if (session.state == SessionState.RESUMING) return parked(session.toPaused(now, failure))
        if (!driver.deleteWorkspace(session)) return now + STEP_DELAY
        val ended = session.moveTo(SessionState.FAILED, now)
            .copy(failure = failure, runnerTokenHash = null, executionStartedAt = null)
        return write(ended, null)
    }

    /**
     * The runner reported a failed preparation, or the server found one: the execution stops; a resume pauses and
     * keeps the workspace (not resumed again by itself), a first execution fails and deletes it.
     */
    private suspend fun preparationFailed(session: CloudSession, now: Instant): Instant? {
        driver.stopExecutions(session)
        if (session.state == SessionState.RESUMING) {
            return parked(session.toPaused(now, session.failure).copy(desiredState = DesiredState.PAUSED))
        }
        if (!driver.deleteWorkspace(session)) return now + STEP_DELAY
        val ended = session.moveTo(SessionState.FAILED, now)
            .copy(runnerTokenHash = null, executionStartedAt = null)
        return write(ended, null)
    }

    private suspend fun startExecution(session: CloudSession, now: Instant): Instant? {
        profiles.startFailure(session)?.let { return blocked(session, now, it) }
        val refused = planFailure(session, now)
        if (refused != null) {
            driver.stopExecutions(session)
            return parked(session.toPaused(now, refused).copy(desiredState = DesiredState.PAUSED))
        }
        driver.ensureWorkspace(session)
        val token = tokens.mint()
        val next = session.copy(
            epoch = session.epoch + 1,
            executionStartedAt = now,
            readyAt = null,
            lastHeartbeatAt = null,
            backgroundSince = null,
            backgroundSeenAt = null,
            runnerTokenHash = tokens.hash(token),
            runnerStoppedAt = null,
            pausingSince = null,
            phase = STARTING_PHASE,
            updatedAt = now,
        )
        // The row commits first: an execution the runtime runs always has its row, never the reverse.
        val stored = repository.compareAndSet(next, now + STEP_DELAY) ?: return now
        driver.startExecution(stored.executionSpec(token))
        return now + STEP_DELAY
    }

    /**
     * The session's cluster is gone or no longer allows its namespace: nothing is asked of the cluster. A first start
     * fails; a resume stays paused with the failure, not resumed again by itself (TeamCode's `resume_blocked`).
     */
    private suspend fun blocked(session: CloudSession, now: Instant, failure: SessionFailure): Instant? {
        if (session.state == SessionState.RESUMING) {
            return parked(session.toPaused(now, failure).copy(desiredState = DesiredState.PAUSED))
        }
        val ended = session.moveTo(SessionState.FAILED, now)
            .copy(failure = failure, runnerTokenHash = null, executionStartedAt = null)
        return write(ended, null)
    }

    private suspend fun running(session: CloudSession, now: Instant): Instant? {
        val status = driver.executionStatus(session)
        val silent = now - (session.lastHeartbeatAt ?: session.readyAt ?: now) > HEARTBEAT_LOSS
        if (relocating(status, silent)) {
            driver.stopExecutions(session)
            val failure = SessionFailure(
                "runner_lost",
                "The machine running the session stopped answering; the session starts again on another runner.",
            )
            return write(session.toPaused(now, failure), now)
        }
        if (silent || status in LOST_STATUSES) {
            driver.stopExecutions(session)
            val failure = SessionFailure(
                "execution_lost",
                "The session's runner stopped answering; the workspace was kept.",
            )
            return parked(session.toPaused(now, failure).copy(desiredState = DesiredState.PAUSED))
        }
        if (session.desiredState != DesiredState.RUNNING) {
            return write(session.toPausing(now, session.failure), now + STEP_DELAY)
        }
        val reason = limitReached(session, now) ?: planFailure(session, now) ?: return now + WATCH_DELAY
        val again = reason.code == IDLE_TIMEOUT && answeredMeanwhile(session)
        val desired = if (again) DesiredState.RUNNING else DesiredState.PAUSED
        return write(session.toPausing(now, reason).copy(desiredState = desired), now + STEP_DELAY)
    }

    /**
     * Whether an answer to a wait of the session came while this execution ran and its agent never took it: its
     * runner took what was there when it started, so an older one, not taken, would never be (no restart loop).
     */
    private suspend fun answeredMeanwhile(session: CloudSession): Boolean {
        val started = session.executionStartedAt ?: session.readyAt ?: return false
        return answers.answeredSince(session.orgId, session.id.toString(), started)
    }

    /**
     * Whether a running execution starts again elsewhere: its machine died, or, on a runtime that relocates, its
     * runner fell silent while the machine still answers for it (the machine's last word predates its death).
     */
    private fun relocating(status: ExecutionStatus, silent: Boolean): Boolean =
        status == ExecutionStatus.HOST_LOST || (silent && driver.relocates && status !in ENDED_STATUSES)

    private suspend fun limitReached(session: CloudSession, now: Instant): SessionFailure? {
        val idleSince = session.lastActivityAt ?: session.readyAt ?: now
        val timeout = session.limits.idleTimeoutMinutes.minutes
        return when {
            now - idleSince >= timeout && !backgroundHolds(session, now, timeout) ->
                SessionFailure(
                    IDLE_TIMEOUT,
                    "Paused after ${session.limits.idleTimeoutMinutes} minutes without a request to the model.",
                )
            now - (session.readyAt ?: now) >= session.limits.maxRunMinutes.minutes ->
                SessionFailure("max_duration", "Paused after ${session.limits.maxRunMinutes} minutes of running.")
            else -> null
        }
    }

    /**
     * Whether the agent's background work still holds off the idle pause. The organization's cap is read only once
     * the session already looks idle.
     */
    private suspend fun backgroundHolds(session: CloudSession, now: Instant, timeout: Duration): Boolean {
        if (session.backgroundSeenAt == null) return false
        val activity = BackgroundWork.activity(session, backgroundWork.maxMinutes(session.orgId)) ?: return false
        return now - activity < timeout
    }

    private suspend fun pausing(session: CloudSession, now: Instant): Instant? {
        val since = session.pausingSince ?: now
        val lastSign = session.lastHeartbeatAt ?: since
        val done = session.runnerStoppedAt != null || now - since > PAUSE_DEADLINE || now - lastSign > HEARTBEAT_LOSS
        if (!done) return now + STEP_DELAY
        driver.stopExecutions(session)
        val paused = session.toPaused(now, session.failure)
        return write(paused, if (paused.desiredState == DesiredState.RUNNING) now else paused.expiresAt)
    }

    /**
     * A paused session: past its [CloudSession.expiresAt], it is terminated with its workspace (TeamCode's
     * `cloud.session.expired`, [CloudSession.TERMINATED_EXPIRED]); asked to run, it resumes and no longer expires;
     * otherwise it is looked at again when it expires.
     */
    private suspend fun paused(session: CloudSession, now: Instant): Instant? {
        val expiresAt = session.expiresAt
        if (expiresAt != null && expiresAt <= now) {
            val expired = session.copy(
                desiredState = DesiredState.TERMINATED,
                terminatedReason = CloudSession.TERMINATED_EXPIRED,
                updatedAt = now,
            )
            return write(expired, now)
        }
        if (session.desiredState == DesiredState.RUNNING) {
            val resuming = session.moveTo(SessionState.RESUMING, now).copy(
                phase = "scheduling",
                executionStartedAt = null,
                failure = null,
                expiresAt = null,
                expiryBackfill = false,
            )
            return write(resuming, now)
        }
        if (session.expiryBackfill) return upgradedExpiry(session, now)
        return expiresAt
    }

    /**
     * A session paused before paused sessions expired gets its expiry, once, from the retention in force at its first
     * step, counted from its pause; one already past it gets [UPGRADE_GRACE], so that nothing is deleted the moment
     * the upgrade lands. With no retention then, it is kept, as a session paused under none.
     */
    private suspend fun upgradedExpiry(session: CloudSession, now: Instant): Instant? {
        val due = retentionDays()?.let { (session.pausedAt ?: now) + it.days }
        val expiresAt = due?.let { if (it <= now) now + UPGRADE_GRACE else it }
        return write(session.copy(expiresAt = expiresAt, expiryBackfill = false, updatedAt = now), expiresAt)
    }

    /**
     * The paused retention in force, read once by the step that pauses the session (or gives it its expiry). A pause
     * never fails on it: a retention that cannot be read is [PausedRetention.FALLBACK_DAYS].
     */
    private suspend fun retentionDays(): Int? = try {
        retention.days()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        PausedRetention.FALLBACK_DAYS
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

    /** Writes a paused session, looked at again when it expires. */
    private suspend fun parked(paused: CloudSession): Instant? = write(paused, paused.expiresAt)

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

    /** Paused: its workspace kept until the retention in force now runs out ([CloudSession.expiresAt]). */
    private suspend fun CloudSession.toPaused(now: Instant, reason: SessionFailure?): CloudSession =
        moveTo(SessionState.PAUSED, now).copy(
            pausedAt = now,
            expiresAt = retentionDays()?.let { now + it.days },
            expiryBackfill = false,
            executionStartedAt = null,
            runnerTokenHash = null,
            runnerStoppedAt = null,
            pausingSince = null,
            readyAt = null,
            phase = null,
            failure = reason,
        )

    /** Terminated, deleted by its owner or an administrator unless it expired; it no longer expires. */
    private fun CloudSession.toTerminated(now: Instant): CloudSession =
        moveTo(SessionState.TERMINATED, now).copy(
            terminatedAt = now,
            terminatedReason = terminatedReason ?: CloudSession.TERMINATED_DELETED,
            expiresAt = null,
        )

    /** Constants and readers of the enclosing type. */
    companion object {
        /** A start must report ready within this time, image pull and repository clone included. */
        private val START_TIMEOUT: Duration = 15.minutes

        /** Why the idle guard pauses a session. */
        private const val IDLE_TIMEOUT = "idle_timeout"

        /** The phase of an execution started, until its runner reports ready. */
        private const val STARTING_PHASE = "starting_runner"

        /** The phase of an execution that waits for a machine with room ([ExecutionStatus.QUEUED]). */
        private const val QUEUED_PHASE = "queued"

        /** A runner silent for this long is lost; the runners list shows it offline after the same time. */
        internal val HEARTBEAT_LOSS: Duration = 90.seconds

        /** What the runtime reports for an execution that is no longer there. */
        private val LOST_STATUSES = setOf(ExecutionStatus.ENDED, ExecutionStatus.FAILED, ExecutionStatus.MISSING)

        /** What the runtime reports for an execution that ended on a machine still there: it never moves. */
        private val ENDED_STATUSES = setOf(ExecutionStatus.ENDED, ExecutionStatus.FAILED)

        /** A pause drains the agent for at most this long. */
        private val PAUSE_DEADLINE: Duration = 2.minutes

        /** A started execution the runtime does not list yet is given this long before it counts as lost. */
        private val MISSING_GRACE: Duration = 60.seconds

        /** The delay between two steps of a move in progress. */
        private val STEP_DELAY: Duration = 2.seconds

        /** The delay between two checks of a running session. */
        private val WATCH_DELAY: Duration = 15.seconds

        /** The delay between two steps of a session whose cluster is unreachable (TeamCode's retry after a failure). */
        private val UNREACHABLE_RETRY: Duration = 30.seconds

        /** How long a session waits for its unreachable cluster before its execution ends without it. */
        private val UNREACHABLE_GRACE: Duration = 10.minutes

        /**
         * How long a session paused before the upgrade, whose retention already ran out, is kept: its owner sees it
         * expire before it does.
         */
        private val UPGRADE_GRACE: Duration = 1.days

        /** The delay between two cleanups of a session deleted while its cluster was unreachable. */
        private val BLOCKED_RECHECK: Duration = 10.minutes

        /** The phase of a session deleted while its cluster was unreachable, until its objects are proven gone. */
        const val CLEANUP_BLOCKED = "cleanup_blocked"

        /** Why a session's execution ended without its cluster. */
        private val CLUSTER_LOST = SessionFailure(
            "cluster_unavailable",
            "The session's cluster stopped opening: it is gone, or its credentials must be entered again. The " +
                "session stopped without it.",
            retryable = true,
        )

        /** Why a deleted session's objects may remain on its cluster. */
        private val CLUSTER_LOST_CLEANUP = SessionFailure(
            "cluster_unavailable",
            "The session's cluster stopped opening: its pods and workspace are deleted once it opens again.",
            retryable = true,
        )
    }
}

/** No answer waits for any session, as long as no module binds [PendingWaitAnswers] (automations does). */
object NoPendingWaitAnswers : PendingWaitAnswers {
    override suspend fun answeredSince(orgId: Uuid, sessionId: String, since: Instant): Boolean = false
}
