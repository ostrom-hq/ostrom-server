// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The states of a transfer, as TeamCode's. An administrator's transfer of their own session starts [ACCEPTED]; a
 * member's offer starts [OFFERED] and waits for the recipient. [ACCEPTED] lasts until the session reached its paused
 * boundary and the owner changed ([COMPLETED]), or the transfer could not happen ([FAILED], [CANCELLED]).
 *
 * @property wire The stable name in the API and the database.
 */
enum class TransferState(val wire: String) {
    OFFERED("offered"),
    ACCEPTED("accepted"),
    COMPLETED("completed"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    FAILED("failed"),
    ;

    /** Whether the transfer may still change the owner. */
    val open: Boolean get() = this == OFFERED || this == ACCEPTED

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * Reads a state from its wire name.
         *
         * @param wire A requested name.
         *
         * @return The state, or null when the name is unknown.
         */
        fun fromWire(wire: String): TransferState? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A change of owner of a cloud session. The session pauses first (the agent stops, its pod or container is gone);
 * the owner changes only at that paused boundary, in one write, after the recipient was checked again.
 *
 * @property id The transfer.
 * @property orgId The organization.
 * @property sessionId The session.
 * @property fromMemberId The owner when the transfer was asked.
 * @property toMemberId The recipient.
 * @property initiatedBy The member who asked (the owner).
 * @property state Where the transfer is.
 * @property resume Resume the session under the recipient once the owner changed.
 * @property version Incremented by every write, for compare-and-set.
 * @property createdAt When it was asked.
 * @property updatedAt When it last changed; for an accepted transfer, when the owner change started (its deadline).
 * @property expiresAt Offers only: when the recipient can no longer accept.
 * @property decidedBy Who accepted, declined or cancelled.
 * @property decidedAt When.
 * @property completedAt When the owner changed.
 * @property atEpoch The last execution run for the former owner; executions after it run for the recipient.
 * @property failure Why a transfer failed or was cancelled by the server.
 * @property idempotencyKey The key of the request that created it, kept 24 hours.
 * @property requestDigest What the key stands for, to refuse the same key for another request.
 */
data class CloudTransfer(
    val id: Uuid,
    val orgId: Uuid,
    val sessionId: Uuid,
    val fromMemberId: String,
    val toMemberId: String,
    val initiatedBy: String,
    val state: TransferState,
    val resume: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant? = null,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    val completedAt: Instant? = null,
    val atEpoch: Long? = null,
    val failure: SessionFailure? = null,
    val idempotencyKey: String? = null,
    val requestDigest: String? = null,
) {
    /** The transfer as a read shows it: an offer past its validity is [TransferState.EXPIRED] before it is recorded. */
    fun asOf(now: Instant): CloudTransfer =
        if (state == TransferState.OFFERED && expiresAt != null && expiresAt <= now) {
            copy(state = TransferState.EXPIRED)
        } else {
            this
        }

    /** Constants of the enclosing type. */
    companion object {
        /** How long a recipient has to accept an offer. */
        val OFFER_VALIDITY: Duration = 7.days

        /** How long an idempotency key is kept. */
        val KEY_RETENTION: Duration = 1.days

        /** How long `transferring` may last: deleting a container or a pod and committing take seconds. */
        val DEADLINE: Duration = 30.minutes
    }
}

/** A member a session can be given to. */
data class TransferRecipient(val id: String, val login: String, val administrator: Boolean)

/** Which of a member's transfers a query lists. */
enum class TransferDirection { INCOMING, OUTGOING, BOTH }

/**
 * A page query over the caller's transfers.
 *
 * @property memberId The caller.
 * @property direction Incoming, outgoing or both.
 * @property states Only these states; every state when empty.
 * @property sessionId Only the transfers of this session.
 * @property before The id the previous page ended at.
 * @property limit The page size.
 */
data class TransferQuery(
    val memberId: String,
    val direction: TransferDirection,
    val states: Set<TransferState>,
    val sessionId: Uuid?,
    val before: Uuid?,
    val limit: Int,
)

/** A session compare-and-set committed in the same transaction as a transfer write. */
data class SessionChange(val expected: CloudSession, val next: CloudSession, val nextReconcileAt: Instant?) {
    init {
        require(next.id == expected.id && next.generation == expected.generation) {
            "A change must derive from the row it replaces."
        }
        SessionStateMachine.requireTransition(expected.state, next.state)
    }
}

/** What [CloudTransfersRepository.open] did. */
sealed interface TransferOpening {
    /** The transfer is stored. */
    data class Opened(val transfer: CloudTransfer) : TransferOpening

    /** The idempotency key had been used with the same request: the first transfer. */
    data class Replayed(val transfer: CloudTransfer) : TransferOpening

    /** The key had been used with another request. */
    data object KeyConflict : TransferOpening

    /** The session has another open transfer. */
    data object Pending : TransferOpening

    /** The session changed meanwhile (or was not found). */
    data object Conflict : TransferOpening
}

/** What [CloudTransfersRepository.complete] did. */
sealed interface TransferCompletion {
    /** The owner changed. */
    data class Completed(val transfer: CloudTransfer, val session: CloudSession) : TransferCompletion

    /** The session or the transfer changed meanwhile; nothing was written. */
    data object Conflict : TransferCompletion
}

/** Storage of transfers. Every write that touches a session is a compare-and-set on its generation. */
interface CloudTransfersRepository {
    /**
     * Reads a transfer.
     *
     * @param orgId The organization.
     * @param id The transfer.
     *
     * @return The transfer, or null.
     */
    suspend fun find(orgId: Uuid, id: Uuid): CloudTransfer?

    /**
     * Lists a member's transfers, newest first.
     *
     * @param orgId The organization.
     * @param query What to list.
     *
     * @return At most `query.limit` transfers.
     */
    suspend fun list(orgId: Uuid, query: TransferQuery): List<CloudTransfer>

    /**
     * Inserts [transfer] under the session's row lock: a repeated idempotency key replays the stored transfer (or
     * conflicts), another open transfer of the session is [TransferOpening.Pending] (an offer past its validity is
     * recorded `expired` first). With [session], its compare-and-set commits in the same transaction.
     *
     * @param transfer The new transfer.
     * @param now The time, to find offers past their validity and keys past their retention.
     * @param session The session change of an accepted transfer.
     */
    suspend fun open(transfer: CloudTransfer, now: Instant, session: SessionChange?): TransferOpening

    /**
     * Writes [next] if the stored transfer still is [expected] (compare-and-set on its version), with [session] in
     * the same transaction.
     *
     * @return The stored transfer, or null when either changed.
     */
    suspend fun update(expected: CloudTransfer, next: CloudTransfer, session: SessionChange? = null): CloudTransfer?

    /**
     * Changes the owner, in one transaction: the session's compare-and-set (with its new owner), the transfer
     * completed, and the session's terminal tickets removed, so nothing issued to the former owner survives.
     */
    suspend fun complete(expected: CloudSession, next: CloudSession, transfer: CloudTransfer): TransferCompletion
}

/** Who a session can be given to, from the organization's members. */
interface TransferRecipients {
    /**
     * Members who can receive a session, ordered by login, at most [limit].
     *
     * @param orgId The organization.
     * @param limit The most members returned.
     */
    suspend fun list(orgId: Uuid, limit: Int): List<TransferRecipient>

    /**
     * The member a request names, by id or login.
     *
     * @param orgId The organization.
     * @param reference A member id or a login.
     *
     * @return The member's id, or null when no login of the organization is it (an id is returned as it is: whether
     *   that member exists is [eligible]'s question).
     */
    suspend fun find(orgId: Uuid, reference: String): String?

    /**
     * Whether [memberId] can still own a session of [orgId] (an active member).
     */
    suspend fun eligible(orgId: Uuid, memberId: String): Boolean
}

/** Whether members may offer their sessions, from the instance's cloud runtime (`memberTransfers`). */
fun interface MemberTransferPolicy {
    /** True when an owner who is not an administrator may offer a session. */
    suspend fun membersMayOffer(): Boolean
}

/** Members always offer, the instance having no setting. */
object AlwaysOfferTransfers : MemberTransferPolicy {
    override suspend fun membersMayOffer(): Boolean = true
}
