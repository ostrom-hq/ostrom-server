// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.kernel.sessions.PendingWaitAnswers
import run.ostrom.server.kernel.sessions.SessionEndListener
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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
 * @param transfers The owner change of an accepted transfer; null where the server offers none (a session then never
 *   holds one).
 * @param retention How long a paused session keeps its workspace, read when it pauses ([CloudSession.expiresAt]).
 * @param backgroundWork How long the agent's background work holds off the idle pause, per organization.
 * @param devices Whether a remote session's host device still exists: one removed while the removal could not end
 *   its sessions ([EndRemovedDeviceSessions]) ends them here, with [RemoteSessionRules.DEVICE_REMOVED]; null where
 *   the server has no signed-in devices (no remote session is ever created there).
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
    private val transfers: CompleteCloudTransferUseCase? = null,
    private val retention: PausedRetention = KeepPausedSessions,
    private val devices: SignedInDevices? = null,
    private val backgroundWork: BackgroundWorkLimit,
) : ReconcileCloudSessionUseCase {
    override suspend fun invoke(session: CloudSession): Instant? {
        val now = clock.now()
        // No pod, workspace, cluster nor plan: only the server's side of its lifecycle moves.
        if (session.kind == SessionKind.REMOTE) return remoteOrRemoved(session, now)
        if (session.state == SessionState.TERMINATED) {
            return when {
                session.phase == CLEANUP_BLOCKED -> blockedCleanup(session, now)
                else -> session.cleanup?.let { proving(session, it, now) }
            }
        }
        // A failed first start's workspace is deleted right away, as a terminated session's.
        val failed = session.state == SessionState.FAILED && session.desiredState != DesiredState.TERMINATED
        if (failed) session.cleanup?.let { return proving(session, it, now) }
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

    @Suppress("CyclomaticComplexMethod") // A flat decision table over the session's state.
    private suspend fun step(session: CloudSession, now: Instant): Instant? =
        when {
            session.transferId != null && transfers != null && session.desiredState == DesiredState.TERMINATED ->
                transfers.abandon(session)
            session.desiredState == DesiredState.TERMINATED -> terminate(session, now)
            session.state == SessionState.FAILED -> null
            session.preparationFailed -> preparationFailed(session, now)
            session.state == SessionState.CREATING || session.state == SessionState.RESUMING -> starting(session, now)
            session.state == SessionState.RUNNING -> running(session, now)
            session.state == SessionState.PAUSING -> pausing(session, now)
            // A server restarted without the orgs module cannot finish a transfer: the session returns to its owner.
            session.transferId != null && transfers == null ->
                write(
                    session.copy(
                        state = SessionState.PAUSED,
                        phase = null,
                        transferId = null,
                        updatedAt = now,
                    ),
                    now,
                )
            session.state == SessionState.TRANSFERRING ->
                if (transfers == null) now + WATCH_DELAY else transfers.step(session)
            transfers != null && session.transferId != null -> transfers.begin(session)
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
            when (outcome.state) {
                SessionState.TERMINATED -> now + BLOCKED_RECHECK
                SessionState.PAUSED -> outcome.expiresAt
                else -> null
            },
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
            // Its workspace's deletion is then proven, as any terminated session's.
            if (proves(session)) {
                val pending = session.copy(phase = CLEANUP_PENDING, failure = null, updatedAt = now)
                return write(pending.copy(workspaceDeletion = deletion(session)), now)
            }
            if (!driver.deleteWorkspace(session)) return now + STEP_DELAY
        } catch (_: RuntimeUnreachableException) {
            return now + BLOCKED_RECHECK
        }
        return write(session.copy(phase = "cleaned", failure = null, updatedAt = now), null)
    }

    /**
     * Stops the executions and terminates the session. On a runtime that [proves][RuntimeDriver.provesDeletion] its
     * workspace's deletion, the session is terminated first and its workspace deleted by the next steps ([cleanup]),
     * keeping a failed session's progress; elsewhere, once the workspace is gone.
     */
    private suspend fun terminate(session: CloudSession, now: Instant): Instant? {
        driver.stopExecutions(session)
        val proving = proves(session)
        if (!proving && !driver.deleteWorkspace(session)) return now + STEP_DELAY
        val terminated = session.toTerminated(now).copy(runnerTokenHash = null, phase = "cleaned").let {
            if (!proving) return@let it
            val deletion = deletion(session)
            val phase = if (deletion.state == DeletionState.PROVEN) "cleaned" else CLEANUP_PENDING
            it.copy(workspaceDeletion = deletion, phase = phase)
        }
        val next = if (terminated.cleanup != null) now else null
        // A lost write (the runner's last heartbeat, sent while its container stopped) asks for a new look.
        repository.compareAndSet(terminated, next) ?: return clock.now()
        // Once written: a lost write is looked at again, and its listeners told then (they are idempotent).
        ended.forEach { it.ended(session.orgId, listOf(session.id.toString())) }
        return next
    }

    /** Whether the session's workspace deletion is proven down to the provider's volume (Kubernetes). */
    private fun proves(session: CloudSession): Boolean = driver.provesDeletion && session.profile != null

    /** The session's deletion progress, started when it has none. */
    private fun deletion(session: CloudSession): WorkspaceDeletion =
        session.workspaceDeletion?.takeIf { it.state != DeletionState.NONE } ?: WorkspaceDeletion(DeletionState.PENDING)

    /** A step of [cleanup]; an unreachable cluster is tried again every [BLOCKED_RECHECK]. */
    private suspend fun proving(session: CloudSession, deletion: WorkspaceDeletion, now: Instant): Instant? = try {
        cleanup(session, deletion, now)
    } catch (_: RuntimeUnreachableException) {
        now + BLOCKED_RECHECK
    }

    /**
     * Deletes the claim, then waits until the bound PersistentVolume, and so the provider's volume behind it, is gone
     * (TeamCode's cleanup). The volume's name and handle are recorded before the claim is deleted. A volume that its
     * reclaim policy keeps, failed, or still there [VOLUME_DELETION_TIMEOUT] after its claim blocks the proof, checked
     * again every [BLOCKED_RECHECK] until an administrator deletes it.
     */
    private suspend fun cleanup(session: CloudSession, w: WorkspaceDeletion, now: Instant): Instant? {
        val claim = driver.workspaceClaim(session) ?: return awaitVolume(session, w, now)
        return deleteClaim(session, w, claim, now)
    }

    /** Records the claim's volume and its provider handle, then deletes the claim. */
    private suspend fun deleteClaim(
        session: CloudSession,
        w: WorkspaceDeletion,
        claim: ClaimObservation,
        now: Instant,
    ): Instant? {
        val volume = claim.volumeName ?: w.volumeName
        val handle = w.volumeHandle ?: volume?.let { handleOf(session, it) }
        var current = session
        if (volume != w.volumeName || handle != w.volumeHandle) {
            val recorded = session.copy(workspaceDeletion = w.copy(volumeName = volume, volumeHandle = handle))
            current = repository.compareAndSet(recorded.copy(updatedAt = now), now) ?: return clock.now()
        }
        driver.deleteWorkspace(current)
        return now + STEP_DELAY
    }

    /** The provider's handle of a volume; none when PersistentVolumes cannot be read, the claim deleted anyway. */
    private suspend fun handleOf(session: CloudSession, volume: String): String? = try {
        driver.volume(session, volume)?.volumeHandle
    } catch (_: ClusterReadForbiddenException) {
        null
    }

    /**
     * The claim is gone: the session proven once its volume is gone too, blocked, or still waiting for it. A claim that
     * was not bound when it was read may have been bound in the race with its deletion: its volume is then found by its
     * claim reference and recorded. A cluster that does not let its PersistentVolumes be read blocks the proof
     * ([WorkspaceDeletion.VOLUME_UNREADABLE]), checked again every [BLOCKED_RECHECK].
     */
    private suspend fun awaitVolume(session: CloudSession, w: WorkspaceDeletion, now: Instant): Instant? {
        val claimDeletedAt = w.claimDeletedAt ?: now
        val volume = try {
            if (w.volumeName == null) driver.claimedVolume(session) else driver.volume(session, w.volumeName)
        } catch (_: ClusterReadForbiddenException) {
            val unreadable = session.blocking(w.copy(claimDeletedAt = claimDeletedAt), VOLUME_UNREADABLE)
            return settle(session, unreadable, now + BLOCKED_RECHECK, now)
        }
        val deleting = w.copy(
            claimDeletedAt = claimDeletedAt,
            volumeName = w.volumeName ?: volume?.name,
            volumeHandle = w.volumeHandle ?: volume?.volumeHandle,
        )
        val (next, nextAt) = when {
            volume == null -> session.copy(
                workspaceDeletion = deleting.copy(
                    state = DeletionState.PROVEN,
                    volumeDeletedAt = now.takeIf { w.volumeName != null },
                    problem = null,
                ),
                phase = "cleaned",
            ) to null
            volume.reclaimPolicy != RECLAIM_DELETE -> session.blocking(
                deleting,
                SessionFailure(
                    WorkspaceDeletion.VOLUME_RETAINED,
                    "The workspace volume ${volume.name} has reclaimPolicy ${volume.reclaimPolicy}; delete the " +
                        "provider volume manually.",
                ),
            ) to now + BLOCKED_RECHECK
            volume.phase == "Failed" || now - claimDeletedAt > VOLUME_DELETION_TIMEOUT -> session.blocking(
                deleting,
                SessionFailure(
                    WorkspaceDeletion.VOLUME_DELETION_STALLED,
                    "The provisioner has not deleted workspace volume ${volume.name}.",
                    retryable = true,
                ),
            ) to now + BLOCKED_RECHECK
            else -> session.copy(workspaceDeletion = deleting, phase = DELETING_VOLUME) to now + STEP_DELAY
        }
        return settle(session, next, nextAt, now)
    }

    /** Writes a cleanup step when it changes the session. */
    private suspend fun settle(session: CloudSession, next: CloudSession, nextAt: Instant?, now: Instant): Instant? =
        if (next == session) nextAt else write(next.copy(updatedAt = now), nextAt)

    private fun CloudSession.blocking(deletion: WorkspaceDeletion, problem: SessionFailure) =
        copy(workspaceDeletion = deletion.copy(state = DeletionState.BLOCKED, problem = problem))

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
        val ended = session.moveTo(SessionState.FAILED, now)
            .copy(failure = failure, runnerTokenHash = null, executionStartedAt = null)
        return failed(session, now, ended)
    }

    /**
     * A first execution failed: its workspace never held work, so it is deleted right away; proven on a runtime that
     * proves it ([cleanup]), before the session fails elsewhere.
     */
    private suspend fun failed(session: CloudSession, now: Instant, ended: CloudSession): Instant? {
        if (proves(session)) {
            val pending = WorkspaceDeletion(DeletionState.PENDING)
            return write(ended.copy(phase = CLEANUP_PENDING, workspaceDeletion = pending), now)
        }
        if (!driver.deleteWorkspace(session)) return now + STEP_DELAY
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
        val ended = session.moveTo(SessionState.FAILED, now)
            .copy(runnerTokenHash = null, executionStartedAt = null)
        return failed(session, now, ended)
    }

    private suspend fun startExecution(session: CloudSession, now: Instant): Instant? {
        profiles.startFailure(session)?.let { return blocked(session, now, it) }
        storageFailure(session)?.let { return blocked(session, now, it) }
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
     * Why a first start's workspace claim is not made (TeamCode's check before its claim): its storage class is gone,
     * or would keep the provider's volume once the claim is deleted. Asked only of a session without its claim yet.
     */
    private suspend fun storageFailure(session: CloudSession): SessionFailure? {
        if (!proves(session) || session.state != SessionState.CREATING) return null
        if (driver.workspaceClaim(session) != null) return null
        // A cluster that does not let its storage classes be read is not checked (the profile's publish neither).
        val storageClass = try {
            driver.storageClass(session)
        } catch (_: ClusterReadForbiddenException) {
            return null
        } ?: return STORAGE_CLASS_MISSING
        if (storageClass.reclaimPolicy == RECLAIM_DELETE) return null
        return SessionFailure(
            "storage_class_retains_volumes",
            "The workspace storage class does not delete provider volumes (reclaimPolicy " +
                "${storageClass.reclaimPolicy}). Use a class with reclaimPolicy Delete.",
        )
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
    private suspend fun write(next: CloudSession, nextReconcileAt: Instant?): Instant? {
        // A pause that an accepted transfer asked for is followed by the owner change: look again at once.
        // Whatever the pause asked for (its expiry, days away): the owner change is not waited for that long.
        val at = if (next.transferId != null && next.state == SessionState.PAUSED) clock.now() else nextReconcileAt
        return if (repository.compareAndSet(next, at) == null) clock.now() else at
    }

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

    /**
     * A remote session (TeamCode's `remote`): its host (`ostrom remote`) starts each execution itself, on its device
     * ([HostRemoteSessionUseCase]). A host that took an epoch and never reported within [HOST_START], or went silent
     * for [HEARTBEAT_LOSS] before it was ready, gives the epoch back and the session waits for its host again; a ready
     * host silent for [HEARTBEAT_LOSS] pauses the session with [RemoteSessionRules.HOST_OFFLINE]; a pause completes
     * once the host stopped, went silent or past [REMOTE_PAUSE_DEADLINE]; deletion and expiry end the session without
     * touching the member's directory, which the server never sees.
     */
    @Suppress("CyclomaticComplexMethod") // A flat decision table over the session's state, as TeamCode's.
    private suspend fun remote(s: CloudSession, now: Instant): Instant? {
        val live = s.executionStartedAt != null
        val seen = s.lastHeartbeatAt ?: s.executionStartedAt ?: now
        val silent = live && now - seen > (if (s.lastHeartbeatAt == null) HOST_START else HEARTBEAT_LOSS)
        return when {
            s.state == SessionState.TERMINATED || s.state == SessionState.FAILED -> null
            s.desiredState == DesiredState.TERMINATED -> remoteTerminated(s, now)
            s.state == SessionState.PAUSED -> remotePaused(s, now)
            s.state == SessionState.CREATING || s.state == SessionState.RESUMING -> when {
                s.desiredState == DesiredState.PAUSED -> parked(s.toPaused(now, s.failure))
                // A host that took an epoch but never reported (or went silent while starting) gives it back.
                silent -> write(
                    s.copy(phase = RemoteSessionRules.WAITING_HOST, executionStartedAt = null, updatedAt = now),
                    null,
                )
                live -> now + WATCH_DELAY
                // Nothing to do until a host starts an execution, which writes the session and asks for a look.
                else -> null
            }
            s.state == SessionState.RUNNING -> when {
                !live || silent -> parked(
                    s.toPaused(now, HOST_OFFLINE_FAILURE).copy(desiredState = DesiredState.PAUSED),
                )
                s.desiredState != DesiredState.RUNNING -> write(s.toPausing(now, s.failure), now + STEP_DELAY)
                else -> remoteIdle(s, now) ?: (now + WATCH_DELAY)
            }
            else -> remotePausing(s, now, live, silent)
        }
    }

    /**
     * The host device of a live remote session when it no longer exists (removed while its sessions could not be
     * ended then, TeamCode ending them in the removal's own transaction); null when it exists or nothing is to end.
     */
    private suspend fun removedHost(s: CloudSession): Uuid? {
        val devices = devices ?: return null
        val host = s.host?.deviceId ?: return null
        val ending = s.desiredState == DesiredState.TERMINATED || s.state == SessionState.TERMINATED
        if (ending || s.state == SessionState.FAILED) return null
        return host.takeIf { it !in devices.devices(setOf(it)) }
    }

    /** A remote session whose device is gone ends with `device_removed`; [remote] decides the rest. */
    private suspend fun remoteOrRemoved(s: CloudSession, now: Instant): Instant? {
        val removed = removedHost(s) ?: return remote(s, now)
        repository.endOnRemovedDevice(SessionRef(s.orgId, s.id), removed, now)
        return now
    }

    /** Terminated without anything to delete: its directory is the member's. */
    private suspend fun remoteTerminated(s: CloudSession, now: Instant): Instant? {
        val terminated = s.toTerminated(now).copy(executionStartedAt = null, runnerTokenHash = null, phase = null)
        repository.compareAndSet(terminated, null) ?: return clock.now()
        ended.forEach { it.ended(s.orgId, listOf(s.id.toString())) }
        return null
    }

    /** Past its expiry it ends; woken, it waits for its host to start the next execution. */
    private suspend fun remotePaused(s: CloudSession, now: Instant): Instant? {
        val expiresAt = s.expiresAt
        if (expiresAt != null && expiresAt <= now) {
            val expired = s.copy(
                desiredState = DesiredState.TERMINATED,
                terminatedReason = CloudSession.TERMINATED_EXPIRED,
                updatedAt = now,
            )
            return write(expired, now)
        }
        if (s.desiredState != DesiredState.RUNNING) return expiresAt
        val waiting = s.moveTo(SessionState.RESUMING, now).copy(
            phase = RemoteSessionRules.WAITING_HOST,
            executionStartedAt = null,
            pausedAt = null,
            expiresAt = null,
            failure = null,
        )
        return write(waiting, null)
    }

    /** Pauses an idle remote session after the organization's `remoteIdleTimeoutMinutes`; 0 never does. */
    private suspend fun remoteIdle(s: CloudSession, now: Instant): Instant? {
        val minutes = s.limits.idleTimeoutMinutes
        if (minutes <= 0) return null
        val timeout = minutes.minutes
        val idleSince = s.lastActivityAt ?: s.readyAt ?: now
        if (now - idleSince < timeout || backgroundHolds(s, now, timeout)) return null
        val reason = SessionFailure(IDLE_TIMEOUT, "Paused after $minutes minutes without activity.")
        return write(s.toPausing(now, reason).copy(desiredState = DesiredState.PAUSED), now + STEP_DELAY)
    }

    /**
     * A resume before the host stopped cancels the pause: the host is alive, it stops draining and takes messages
     * again. Otherwise the pause completes once the host stopped, went silent, or past [REMOTE_PAUSE_DEADLINE].
     */
    private suspend fun remotePausing(s: CloudSession, now: Instant, live: Boolean, silent: Boolean): Instant? {
        val since = s.pausingSince ?: now
        val hostAlive = live && !silent && s.runnerStoppedAt == null
        if (hostAlive && s.desiredState == DesiredState.RUNNING) {
            val running = s.moveTo(SessionState.RUNNING, now)
                .copy(phase = null, pausingSince = null, failure = null, lastActivityAt = now)
            return write(running, now)
        }
        val done = !live || silent || s.runnerStoppedAt != null || now - since > REMOTE_PAUSE_DEADLINE
        if (!done) return now + STEP_DELAY
        val paused = s.toPaused(now, s.failure).copy(desiredState = s.desiredState)
        return write(paused, if (paused.desiredState == DesiredState.RUNNING) now else paused.expiresAt)
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        /** How long a remote host may hold a new epoch without reporting (TeamCode's `HOST_START_MILLIS`). */
        private val HOST_START: Duration = 120.seconds

        /** A remote host drains its turn and checkpoints for at most this long (TeamCode's pausing deadline). */
        private val REMOTE_PAUSE_DEADLINE: Duration = 15.minutes

        /** Why a remote session whose host went silent paused. */
        private val HOST_OFFLINE_FAILURE = SessionFailure(
            RemoteSessionRules.HOST_OFFLINE,
            "The host device stopped responding; the session resumes when its host starts again.",
            retryable = true,
        )

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

        /** How long a PersistentVolume may outlive its claim before its deletion is blocked (TeamCode's 1 h). */
        private val VOLUME_DELETION_TIMEOUT: Duration = 1.hours

        /** The phase of a session whose workspace claim is being deleted. */
        const val CLEANUP_PENDING = "cleanup_pending"

        /** The phase of a session whose claim is gone, while its PersistentVolume is awaited. */
        private const val DELETING_VOLUME = "deleting_volume"

        /** The claim is deleted, but the cluster does not let its PersistentVolume be read. */
        private val VOLUME_UNREADABLE = SessionFailure(
            WorkspaceDeletion.VOLUME_UNREADABLE,
            "The cluster's service account cannot read PersistentVolumes, so the workspace volume's deletion cannot " +
                "be proven: grant it get and list on persistentvolumes (a ClusterRole).",
            retryable = true,
        )

        /** Why a first start makes no claim: its storage class is gone. */
        private val STORAGE_CLASS_MISSING = SessionFailure(
            "storage_class_missing",
            "The workspace storage class does not exist on the cluster.",
        )

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
