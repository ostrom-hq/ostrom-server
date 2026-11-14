// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What a member asks for when giving a session away.
 *
 * @property toMember The recipient's member id or login.
 * @property resume Resume the session for the recipient once the owner changed.
 */
data class TransferRequest(val toMember: String, val resume: Boolean)

/** An idempotency key and what it stands for. */
data class TransferIdempotency(val key: String, val requestDigest: String)

/** What [CloudTransfersUseCase.request] did. */
data class TransferResult(val transfer: CloudTransfer, val replayed: Boolean)

/**
 * Ownership transfers (`runners.cloud.transfer.v1`, TeamCode's `CloudTransferService`). Routes write here; the
 * reconciler changes the owner once the session reached its paused boundary ([CompleteCloudTransferUseCase]).
 *
 * - An administrator transfers their own session at once: the transfer starts `accepted`.
 * - An owner who is not an administrator offers their own session, when the instance allows it; the recipient accepts
 *   within the offer's validity. Nobody can take a session they do not own.
 * - Accepting pauses the session (the agent stops, its container or pod is gone); the owner changes only then.
 */
// One entry per route of the family, as the sessions' change use case.
@Suppress("TooManyFunctions")
interface CloudTransfersUseCase {
    /**
     * Asks to give one of the caller's sessions to another member.
     *
     * @param caller The owner.
     * @param principal Who asks, for the audit log.
     * @param sessionId The session.
     * @param request The recipient and what follows.
     * @param idempotency The request's key and digest.
     *
     * @return The transfer, and whether it replays an earlier request of the same key.
     */
    suspend fun request(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        sessionId: Uuid,
        request: TransferRequest,
        idempotency: TransferIdempotency,
    ): TransferResult

    /** The members the caller can give a session to, by login, at most [MAX_RECIPIENTS]. */
    suspend fun recipients(caller: Caller): List<TransferRecipient>

    /** The recipient accepts an offer; the session then pauses and changes owner. Idempotent. */
    suspend fun accept(caller: Caller, principal: AuthenticatedPrincipal, id: Uuid, resume: Boolean?): CloudTransfer

    /** The recipient declines an offer. */
    suspend fun decline(caller: Caller, principal: AuthenticatedPrincipal, id: Uuid): CloudTransfer

    /**
     * The former owner withdraws an offer, or an administrator cancels an accepted transfer before the owner change
     * starts; the session stays paused or keeps pausing.
     */
    suspend fun cancel(caller: Caller, principal: AuthenticatedPrincipal, id: Uuid): CloudTransfer

    /** One transfer, visible to its two members only. */
    suspend fun get(caller: Caller, id: Uuid): CloudTransfer

    /** A page of the caller's transfers, newest first. */
    suspend fun list(caller: Caller, query: TransferListing): List<CloudTransfer>

    /** Constants of the enclosing type. */
    companion object {
        /** A picker's bound; a larger organization names the recipient by id or login. */
        const val MAX_RECIPIENTS: Int = 500
    }
}

/** A page query of the caller's transfers, before it is bound to the caller. */
data class TransferListing(
    val direction: TransferDirection,
    val states: Set<TransferState>,
    val sessionId: Uuid?,
    val before: Uuid?,
    val limit: Int,
)

/**
 * [CloudTransfersUseCase] on the sessions, the transfers, the members and the instance's policy.
 *
 * @param sessions The sessions.
 * @param transfers The transfers.
 * @param recipients The organization's members.
 * @param policy Whether members may offer their sessions.
 * @param audit The organization's audit log.
 * @param clock The time.
 */
// A refusal per rule of the family, each its own answer.
@Suppress("TooManyFunctions", "ThrowsCount", "LongParameterList")
class CloudTransfersUseCaseImpl(
    private val sessions: CloudSessionsRepository,
    private val transfers: CloudTransfersRepository,
    private val recipients: TransferRecipients,
    private val policy: MemberTransferPolicy,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : CloudTransfersUseCase {
    override suspend fun request(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        sessionId: Uuid,
        request: TransferRequest,
        idempotency: TransferIdempotency,
    ): TransferResult {
        val now = clock.now()
        val session = sessions.ownedBy(caller, sessionId)
        transferable(session)
        // The recipient is named by id or login; one nobody has fails the checks like an ineligible one.
        val recipient = recipients.find(caller.orgId, request.toMember)
        val toMemberId = recipient ?: request.toMember
        if (toMemberId == session.ownerId) throw InvalidTransferException("The recipient already owns this session.")
        if (!caller.admin && !policy.membersMayOffer()) throw TransferDisabledException()
        requireEligible(session, toMemberId)
        val transfer = newTransfer(caller, session, toMemberId, request, idempotency, now)
        val change = if (caller.admin) change(session, toward(session, transfer, now), now) else null
        return when (val opening = transfers.open(transfer, now, change)) {
            is TransferOpening.Opened -> {
                record(principal, caller, if (caller.admin) REQUESTED else OFFERED, opening.transfer)
                TransferResult(opening.transfer, replayed = false)
            }
            is TransferOpening.Replayed -> TransferResult(opening.transfer, replayed = true)
            TransferOpening.KeyConflict -> throw IdempotencyConflictException()
            TransferOpening.Pending -> throw TransferPendingException()
            TransferOpening.Conflict -> throw SessionBusyException()
        }
    }

    private fun newTransfer(
        caller: Caller,
        session: CloudSession,
        toMemberId: String,
        request: TransferRequest,
        idempotency: TransferIdempotency,
        now: Instant,
    ): CloudTransfer {
        return CloudTransfer(
            id = Uuid.random(),
            orgId = caller.orgId,
            sessionId = session.id,
            fromMemberId = session.ownerId,
            toMemberId = toMemberId,
            initiatedBy = caller.memberId,
            state = if (caller.admin) TransferState.ACCEPTED else TransferState.OFFERED,
            resume = request.resume,
            version = 1,
            createdAt = now,
            updatedAt = now,
            expiresAt = if (caller.admin) null else now + CloudTransfer.OFFER_VALIDITY,
            decidedBy = if (caller.admin) caller.memberId else null,
            decidedAt = if (caller.admin) now else null,
            idempotencyKey = idempotency.key,
            requestDigest = idempotency.requestDigest,
        )
    }

    override suspend fun recipients(caller: Caller): List<TransferRecipient> {
        if (!caller.admin && !policy.membersMayOffer()) throw TransferDisabledException()
        return recipients.list(caller.orgId, CloudTransfersUseCase.MAX_RECIPIENTS)
    }

    override suspend fun accept(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        resume: Boolean?,
    ): CloudTransfer {
        repeat(ATTEMPTS) {
            val transfer = visible(caller, id)
            if (transfer.toMemberId != caller.memberId) {
                throw TransferForbiddenException("Only the recipient can accept this transfer.")
            }
            if (transfer.state == TransferState.ACCEPTED || transfer.state == TransferState.COMPLETED) return transfer
            val now = clock.now()
            requireOpen(transfer, now)
            // Disabling member transfers also stops offers made before.
            if (!caller.admin && !policy.membersMayOffer()) throw TransferDisabledException()
            val session = sessions.get(transfer.orgId, transfer.sessionId) ?: throw SessionNotRunningException()
            if (session.ownerId != transfer.fromMemberId) {
                close(transfer, caller, TransferState.CANCELLED, now, OWNER_CHANGED)
            }
            if (session.state.isFinal || session.desiredState == DesiredState.TERMINATED) {
                close(transfer, caller, TransferState.CANCELLED, now, SESSION_ENDED)
            }
            transferable(session, transfer.id)
            requireEligible(session, transfer.toMemberId)
            val accepted = transfer.copy(
                state = TransferState.ACCEPTED,
                resume = resume ?: transfer.resume,
                decidedBy = caller.memberId,
                decidedAt = now,
                updatedAt = now,
                version = transfer.version + 1,
            )
            transfers.update(transfer, accepted, change(session, toward(session, accepted, now), now))?.let {
                record(principal, caller, ACCEPTED, it)
                return it
            }
        }
        throw SessionBusyException()
    }

    override suspend fun decline(caller: Caller, principal: AuthenticatedPrincipal, id: Uuid): CloudTransfer {
        repeat(ATTEMPTS) {
            val transfer = visible(caller, id)
            if (transfer.toMemberId != caller.memberId) {
                throw TransferForbiddenException("Only the recipient can decline this transfer.")
            }
            if (transfer.state == TransferState.DECLINED) return transfer
            val now = clock.now()
            if (transfer.state != TransferState.OFFERED) throw TransferNotOpenException()
            requireOpen(transfer, now)
            transfers.update(transfer, decided(transfer, caller, TransferState.DECLINED, now, null))?.let {
                record(principal, caller, DECLINED, it)
                return it
            }
        }
        throw SessionBusyException()
    }

    override suspend fun cancel(caller: Caller, principal: AuthenticatedPrincipal, id: Uuid): CloudTransfer {
        repeat(ATTEMPTS) {
            val transfer = visible(caller, id)
            if (transfer.fromMemberId != caller.memberId) {
                throw TransferForbiddenException("Only the session owner can cancel this transfer.")
            }
            if (transfer.state == TransferState.CANCELLED) return transfer
            if (!transfer.state.open) throw TransferNotOpenException()
            // Accepted means the owner already gave the session away; only an administrator may stop it now.
            if (transfer.state == TransferState.ACCEPTED && !caller.admin) {
                throw TransferForbiddenException("The transfer was accepted. Ask an administrator to cancel it.")
            }
            val now = clock.now()
            val change = if (transfer.state == TransferState.ACCEPTED) release(transfer, now) else null
            val cancelled = decided(transfer, caller, TransferState.CANCELLED, now, null)
            transfers.update(transfer, cancelled, change)?.let {
                record(principal, caller, CANCELLED, it)
                return it
            }
        }
        throw SessionBusyException()
    }

    override suspend fun get(caller: Caller, id: Uuid): CloudTransfer = visible(caller, id).asOf(clock.now())

    override suspend fun list(caller: Caller, query: TransferListing): List<CloudTransfer> {
        ensureValid(query.limit in 1..MAX_PAGE) { "The limit must be between 1 and $MAX_PAGE." }
        val page = transfers.list(
            caller.orgId,
            TransferQuery(caller.memberId, query.direction, query.states, query.sessionId, query.before, query.limit),
        )
        val now = clock.now()
        return page.map { it.asOf(now) }
    }

    /** The accepted transfer's session goes back to what it was: no transfer, still paused or pausing. */
    private suspend fun release(transfer: CloudTransfer, now: Instant): SessionChange? {
        val session = sessions.get(transfer.orgId, transfer.sessionId)?.takeIf { it.transferId == transfer.id }
            ?: return null
        if (session.state == SessionState.TRANSFERRING) throw TransferInProgressException()
        return change(session, session.copy(transferId = null, updatedAt = now), now)
    }

    /** Visible to the two members of the transfer; anyone else gets `404`. */
    private suspend fun visible(caller: Caller, id: Uuid): CloudTransfer {
        val transfer = transfers.find(caller.orgId, id) ?: throw TransferNotFoundException()
        if (caller.memberId != transfer.fromMemberId && caller.memberId != transfer.toMemberId) {
            throw TransferNotFoundException()
        }
        return transfer
    }

    /** An open transfer whose offer is past its validity is expired on the spot. */
    private suspend fun requireOpen(transfer: CloudTransfer, now: Instant) {
        if (!transfer.state.open) throw TransferNotOpenException()
        if (transfer.state == TransferState.OFFERED && transfer.expiresAt != null && transfer.expiresAt <= now) {
            transfers.update(
                transfer,
                transfer.copy(state = TransferState.EXPIRED, updatedAt = now, version = transfer.version + 1),
            )
            throw TransferExpiredException()
        }
    }

    private suspend fun requireEligible(session: CloudSession, memberId: String) {
        if (!recipients.eligible(session.orgId, memberId)) {
            throw RecipientIneligibleException(listOf("recipient_not_found"))
        }
    }

    private suspend fun close(
        transfer: CloudTransfer,
        caller: Caller,
        state: TransferState,
        now: Instant,
        problem: SessionFailure,
    ): Nothing {
        transfers.update(transfer, decided(transfer, caller, state, now, problem))
        throw CloudSessionStateException(problem.message)
    }

    private fun decided(
        transfer: CloudTransfer,
        caller: Caller,
        state: TransferState,
        now: Instant,
        problem: SessionFailure?,
    ) = transfer.copy(
        state = state,
        decidedBy = caller.memberId,
        decidedAt = now,
        failure = problem,
        updatedAt = now,
        version = transfer.version + 1,
    )

    private suspend fun record(principal: AuthenticatedPrincipal, caller: Caller, event: String, t: CloudTransfer) =
        audit.record(
            OrgAuditRecord(
                caller.orgId,
                principal,
                event,
                SUBJECT,
                t.sessionId.toString(),
                auditDetails("transfer" to t.id, "from" to t.fromMemberId, "to" to t.toMemberId),
            ),
        )

    /** Constants and rules of the enclosing type. */
    companion object {
        private const val ATTEMPTS = 5
        private const val MAX_PAGE = 50
        private const val SUBJECT = "cloud_session"
        private const val REQUESTED = "cloud.transfer.requested"
        private const val OFFERED = "cloud.transfer.offered"
        private const val ACCEPTED = "cloud.transfer.accepted"
        private const val DECLINED = "cloud.transfer.declined"
        private const val CANCELLED = "cloud.transfer.cancelled"
        private val OWNER_CHANGED = SessionFailure("owner_changed", "The session changed owner since the offer.")
        private val SESSION_ENDED = SessionFailure(
            "session_not_running",
            "The cloud session is terminated or being deleted.",
        )

        /**
         * What a transfer may start from: anything but creation and the end of the session. A session that is
         * transferring or whose transfer is accepted takes no other, except [allowing] (the one being accepted).
         *
         * @throws SessionNotRunningException When the session is over.
         * @throws SessionPreparingException When it is being created.
         * @throws TransferPendingException When it already has an accepted transfer.
         */
        fun transferable(session: CloudSession, allowing: Uuid? = null) {
            when {
                session.state.isFinal || session.desiredState == DesiredState.TERMINATED ->
                    throw SessionNotRunningException()
                session.state == SessionState.CREATING -> throw SessionPreparingException()
                session.state == SessionState.TRANSFERRING ||
                    (session.transferId != null && session.transferId != allowing) -> throw TransferPendingException()
            }
        }

        /**
         * The session moving toward an accepted transfer: it pauses (the agent stops, the execution is gone) unless
         * it already is, and keeps [CloudSession.transferId] until the reconciler changed the owner. A pending resume
         * is cancelled: a resume happens only after the change, for the recipient, when the transfer asks for it.
         */
        fun toward(session: CloudSession, transfer: CloudTransfer, now: Instant): CloudSession =
            session.copy(transferId = transfer.id, desiredState = DesiredState.PAUSED, updatedAt = now)

        private fun change(expected: CloudSession, next: CloudSession, now: Instant) =
            SessionChange(expected, next, now)
    }
}
