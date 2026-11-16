// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The reconciler's side of a transfer (TeamCode's `CompleteCloudTransferUseCase`), run under the session's claim. The
 * owner changes only at a paused boundary:
 *
 * 1. `paused` with an accepted transfer moves to `transferring`, which refuses resumes, wakes and new transfers. The
 *    transfer's `updatedAt` marks the start; past [CloudTransfer.DEADLINE] (a cluster that never confirms the end of an
 *    execution) the transfer fails with `transfer_timeout` and the session returns to `paused` for its former owner.
 * 2. No execution of the session may exist: any one left is stopped and confirmed gone first, so nothing that ran for
 *    the former owner (their Git token, variables, agent process) survives the change.
 * 3. The recipient is checked again on fresh data; one no longer eligible fails the transfer, and the session returns
 *    to `paused` for its former owner.
 * 4. One transaction changes the owner, completes the transfer and removes the session's terminal tickets
 *    ([CloudTransfersRepository.complete]). The next execution starts a new epoch for the recipient, and every
 *    credential of the pod is resolved for the recipient then; the workspace is marked untrusted until it is ready.
 *
 * @param repository The sessions.
 * @param transfers The transfers.
 * @param driver The runtime, to confirm no execution is left.
 * @param recipients The organization's members, checked again at the boundary.
 * @param audit The organization's audit log.
 * @param clock The time.
 * @param retention How long a paused session keeps its workspace: the recipient gets all of it, as TeamCode's, so a
 *   session about to expire is not handed over for deletion.
 * @param caps The recipient's retained cap, checked again in the owner change's transaction: a session created for
 *   them since the acceptance fails the transfer with `cloud_session_limit`.
 */
@Suppress("LongParameterList") // The ports a step reads and writes, each named at its one call site.
class CompleteCloudTransferUseCase(
    private val repository: CloudSessionsRepository,
    private val transfers: CloudTransfersRepository,
    private val driver: RuntimeDriver,
    private val recipients: TransferRecipients,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
    private val retention: PausedRetention = KeepPausedSessions,
    private val caps: MemberSessionCaps = NoMemberSessionCaps,
) {
    /** A paused session with an accepted transfer starts its owner change. */
    suspend fun begin(session: CloudSession): Instant? {
        val now = clock.now()
        val transfer = accepted(session) ?: return release(session.copy(transferId = null), now)
        // One transaction: the session enters `transferring` and the transfer records when (its deadline starts there).
        val transferring = session.copy(state = SessionState.TRANSFERRING, phase = PHASE, updatedAt = now)
        val change = SessionChange(session, transferring, now)
        transfers.update(transfer, transfer.copy(updatedAt = now, version = transfer.version + 1), change)
        return now
    }

    private suspend fun handedOver(session: CloudSession, transfer: CloudTransfer, now: Instant): CloudSession {
        val owned = session.copy(
            ownerId = transfer.toMemberId,
            transferId = null,
            failure = null,
            untrustedWorkspace = true,
            // The former owner's choice to share their GitHub access does not carry over to the recipient's token.
            githubAccess = if (session.githubAccess == GitHubAccessMode.SHARED) {
                GitHubAccessMode.OWNER
            } else {
                session.githubAccess
            },
            updatedAt = now,
        )
        return if (transfer.resume) {
            owned.copy(
                state = SessionState.RESUMING,
                phase = "scheduling",
                desiredState = DesiredState.RUNNING,
                executionStartedAt = null,
                expiresAt = null,
                expiryBackfill = false,
            )
        } else {
            owned.copy(
                state = SessionState.PAUSED,
                phase = null,
                desiredState = DesiredState.PAUSED,
                pausedAt = now,
                expiresAt = retentionDays()?.let { now + it.days },
                expiryBackfill = false,
            )
        }
    }

    /** The retention in force; a retention that cannot be read is [PausedRetention.FALLBACK_DAYS]. */
    private suspend fun retentionDays(): Int? = try {
        retention.days()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        PausedRetention.FALLBACK_DAYS
    }

    /** One bounded step of `transferring`. */
    @Suppress("ReturnCount") // A decision table read top to bottom.
    suspend fun step(session: CloudSession): Instant {
        val now = clock.now()
        val transfer = accepted(session)
            ?: return release(session.copy(state = SessionState.PAUSED, phase = null, transferId = null), now)
        // Checked before any runtime call, so an unreachable runtime cannot hold the session in `transferring`.
        if (now - transfer.updatedAt > CloudTransfer.DEADLINE) {
            return fail(
                session,
                transfer,
                now,
                "transfer_timeout",
                "The owner change did not finish in time. The session stays with its owner; offer it again.",
            )
        }
        driver.stopExecutions(session)
        if (driver.executionStatus(session) !in GONE) return now + STEP_DELAY
        if (!recipients.eligible(session.orgId, transfer.toMemberId)) {
            return fail(session, transfer, now, "recipient_unavailable", "The recipient is no longer an active member.")
        }
        val next = handedOver(session, transfer, now)
        val completed = transfer.copy(
            state = TransferState.COMPLETED,
            completedAt = now,
            atEpoch = session.epoch,
            updatedAt = now,
            version = transfer.version + 1,
        )
        val completion = transfers.complete(session, next, completed, caps.retainedLimit())
        if (completion == TransferCompletion.RecipientAtLimit) {
            return fail(
                session,
                transfer,
                now,
                CloudSessionRetainedLimitException.CODE,
                "The recipient already retains the maximum number of cloud sessions.",
            )
        }
        if (completion is TransferCompletion.Completed) {
            audit.record(
                OrgAuditRecord(
                    session.orgId,
                    TRANSFER_ACTOR,
                    TRANSFERRED,
                    SUBJECT,
                    session.id.toString(),
                    auditDetails(
                        "transfer" to transfer.id,
                        "from" to transfer.fromMemberId,
                        "to" to transfer.toMemberId,
                        "initiatedBy" to transfer.initiatedBy,
                        "atEpoch" to session.epoch,
                    ),
                ),
            )
        }
        return now
    }

    /** The session ended (deleted, expired or failed) before its owner changed: the transfer is cancelled. */
    suspend fun abandon(session: CloudSession): Instant? {
        val now = clock.now()
        val transfer = session.transferId?.let { transfers.find(session.orgId, it) }
        val next = if (session.state == SessionState.TRANSFERRING) {
            session.copy(state = SessionState.PAUSED, phase = null, transferId = null, updatedAt = now)
        } else {
            session.copy(transferId = null, updatedAt = now)
        }
        if (transfer == null || !transfer.state.open) return release(next, now)
        val cancelled = transfer.copy(
            state = TransferState.CANCELLED,
            failure = SessionFailure("session_not_running", "The session ended before its owner changed."),
            updatedAt = now,
            version = transfer.version + 1,
        )
        transfers.update(transfer, cancelled, SessionChange(session, next, now))
        return now
    }

    private suspend fun accepted(session: CloudSession): CloudTransfer? =
        session.transferId?.let { transfers.find(session.orgId, it) }
            ?.takeIf { it.state == TransferState.ACCEPTED && it.sessionId == session.id }

    /** Drops a transfer the session names but nobody holds open (it cannot happen through the use cases). */
    private suspend fun release(next: CloudSession, now: Instant): Instant {
        repository.compareAndSet(next.copy(updatedAt = now), now)
        return now
    }

    private suspend fun fail(
        session: CloudSession,
        transfer: CloudTransfer,
        now: Instant,
        code: String,
        message: String,
    ): Instant {
        val failed = transfer.copy(
            state = TransferState.FAILED,
            failure = SessionFailure(code, message),
            updatedAt = now,
            version = transfer.version + 1,
        )
        val back = session.copy(state = SessionState.PAUSED, phase = null, transferId = null, updatedAt = now)
        transfers.update(transfer, failed, SessionChange(session, back, now))
        return now
    }

    /** Constants of the enclosing type. */
    companion object {
        /** The phase of a session whose owner is changing. */
        const val PHASE: String = "transferring"

        private const val TRANSFERRED = "cloud.session.transferred"
        private const val SUBJECT = "cloud_session"
        private val GONE = setOf(ExecutionStatus.MISSING, ExecutionStatus.ENDED, ExecutionStatus.FAILED)
        private val STEP_DELAY: Duration = 2.seconds
        private val TRANSFER_ACTOR = AuthenticatedPrincipal("cloud-sessions", PrincipalKind.SERVICE, emptySet())
    }
}
