// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/Notifications.kt; see NOTICE.
package run.ostrom.server.notifications.domain

import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.OrgEventType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where a delivery stands. The [wire] name is stored.
 *
 * @property wire Its name in the database.
 */
enum class DeliveryState(val wire: String) {
    /** To attempt, now or at its next attempt. */
    PENDING("pending"),

    /** Delivered. */
    DELIVERED("delivered"),

    /** Refused for good, out of attempts, or given up. */
    FAILED("failed"),
}

/**
 * A row of the deliveries queue: a member's notification on a channel ([NotificationDelivery], TeamCode's), or the
 * request for a yes an approver is told of ([ApprovalDelivery], Ostrom's own, on the same mechanics).
 */
sealed interface QueuedDelivery {
    /** The delivery's id. */
    val id: Uuid

    /** The organization. */
    val orgId: Uuid

    /** The channel's id. */
    val channel: String

    /** The attempts made so far. */
    val attempts: Int
}

/**
 * One notification queued for one channel (TeamCode's `NotificationDelivery`).
 *
 * @property notification The notification; its id is the delivery's.
 * @property channel The channel's id.
 * @property attempts The attempts made so far.
 */
data class NotificationDelivery(
    val notification: Notification,
    override val channel: String,
    override val attempts: Int,
) : QueuedDelivery {
    override val id: Uuid get() = notification.id
    override val orgId: Uuid get() = notification.orgId
}

/**
 * The request for a yes one approver is told of on one way ([PUSH_CHANNEL] or [ApprovalRules.EMAIL_CHANNEL]): an
 * Ostrom extension of TeamCode's queue, its row of kind [ApprovalRules.KIND]. It is not a [NotificationKind] and no
 * member switch governs it: the approver's `requests` preference and the instance's mail do.
 *
 * @property id The delivery's id.
 * @property orgId The organization.
 * @property memberId The approver's membership.
 * @property approver The approver's login, as the gate names them.
 * @property approvalId The approver's request.
 * @property gateId Its gate.
 * @property channel `push` or `email`.
 * @property attempts The attempts made so far.
 * @property eventSeq The `cloud.approval.requested` event it comes from.
 * @property title What the approver is told, kept for support.
 * @property body The gate's label and repository, kept for support.
 * @property createdAt When it was queued.
 */
data class ApprovalDelivery(
    override val id: Uuid,
    override val orgId: Uuid,
    val memberId: Uuid,
    val approver: String,
    val approvalId: Uuid,
    val gateId: Uuid,
    override val channel: String,
    override val attempts: Int,
    val eventSeq: Long,
    val title: String,
    val body: String,
    val createdAt: Instant,
) : QueuedDelivery {
    /** The row's `collapse_key`, for support; phones collapse a request's pushes by its id (EncryptedPushSender). */
    val collapseKey: String get() = "approval:$approvalId"
}

/**
 * What an attempt leaves on its delivery.
 *
 * @property state Where it stands now.
 * @property attempts The attempts made, this one included.
 * @property nextAttemptAt When it is attempted again, for a pending one.
 * @property error A short code of ours, null once delivered.
 */
data class DeliveryOutcome(
    val state: DeliveryState,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val error: String?,
)

/**
 * The deliveries queue (`notifications_deliveries`, TeamCode's `notification_deliveries`): unique per organization,
 * event, member and channel, so a page of the journal is never queued twice; claimed under a lease, so a replica that
 * dies mid-attempt loses nothing.
 */
interface NotificationDeliveriesRepository {
    /**
     * Queues [deliveries] and moves the router's journal cursor from [JournalPosition.from] to [through], in one
     * transaction.
     *
     * @param position Where the router stood when it read the page.
     * @param through Where it stands once the page is queued.
     * @param deliveries The page's deliveries, all of [JournalPosition.orgId].
     *
     * @return False, with nothing written, when another replica queued the page meanwhile.
     */
    suspend fun enqueue(position: JournalPosition, through: Long, deliveries: List<QueuedDelivery>): Boolean

    /**
     * @param now The time.
     * @param limit The most organizations.
     *
     * @return The organizations with a delivery due and unclaimed, the longest waiting first.
     */
    suspend fun workingOrgs(now: Instant, limit: Int): List<Uuid>

    /**
     * Claims up to [limit] of the organization's due deliveries until [now] plus [lease], skipping those another
     * replica is claiming. A delivery of a kind this server does not know (neither a [NotificationKind] nor
     * [ApprovalRules.KIND]) is failed instead.
     *
     * @param orgId The organization.
     * @param limit The most deliveries.
     * @param now The time.
     * @param lease How long the claim holds.
     *
     * @return The claimed deliveries, the longest due first.
     */
    suspend fun claimDue(orgId: Uuid, limit: Int, now: Instant, lease: Duration): List<QueuedDelivery>

    /**
     * Records an attempt and releases the claim.
     *
     * @param orgId The organization.
     * @param id The delivery.
     * @param outcome What the attempt left.
     * @param now The time.
     */
    suspend fun record(orgId: Uuid, id: Uuid, outcome: DeliveryOutcome, now: Instant)

    /**
     * Across organizations: fails the pending deliveries created before [giveUpBefore] and unclaimed (`expired`), and
     * deletes the finished ones created before [deleteBefore], at most [limit] of each.
     *
     * @param now The time.
     * @param giveUpBefore The oldest a pending delivery may be.
     * @param deleteBefore The oldest a finished delivery is kept.
     * @param limit The most rows of each kind.
     *
     * @return The rows given up and deleted.
     */
    suspend fun maintain(now: Instant, giveUpBefore: Instant, deleteBefore: Instant, limit: Int): Long

    /**
     * Erases an organization's deliveries, once it is deleted.
     *
     * @param orgId The organization.
     */
    suspend fun deleteAll(orgId: Uuid)
}

/** The worker's bounds and retry policy (TeamCode's `NotificationService` and `DeliverNotificationsUseCase`). */
object DeliveryRules {
    /** The journal reader's name: its cursor in `events_readers`. */
    const val READER: String = "notifications"

    /** The journal types the router reads: the members' notifications' and the approvals'. */
    val SOURCE_TYPES: Set<OrgEventType> = NotificationRules.SOURCE_TYPES + ApprovalRules.SOURCE_TYPES

    /** The most attempts of a delivery. */
    const val MAX_ATTEMPTS: Int = 5

    /** The organizations a pass fans out and attempts. */
    const val ORGS_PER_PASS: Int = 100

    /** The journal events a fan-out reads per organization. */
    const val EVENTS_PER_FANOUT: Int = 200

    /** The deliveries a pass attempts. */
    const val BATCH: Int = 50

    /** The rows of each kind a maintenance pass gives up or deletes. */
    const val MAINTAIN_BATCH: Int = 1_000

    /** How long a claim holds. */
    val LEASE: Duration = 1.minutes

    /** How long a channel may take over one batch. */
    val SEND_DEADLINE: Duration = 15.seconds

    /** A notification late by more than this is no longer worth sending: "the agent waits for you", an hour later. */
    val GIVE_UP: Duration = 1.hours

    /** Finished deliveries keep their text this long, for support; then they are deleted. */
    val RETENTION: Duration = 1.days

    /** The waits before attempts 2 to 5. */
    val BACKOFF: List<Duration> = listOf(10.seconds, 30.seconds, 2.minutes, 10.minutes)

    /**
     * @param attempts The attempts made before this one.
     * @param result What this one produced.
     * @param now The time.
     *
     * @return What it leaves on the delivery: delivered, failed for good or out of attempts, or pending until its
     *   backoff.
     */
    fun outcome(attempts: Int, result: ChannelResult, now: Instant): DeliveryOutcome {
        val made = attempts + 1
        return when {
            result.outcome == ChannelResult.Outcome.DELIVERED ->
                DeliveryOutcome(DeliveryState.DELIVERED, made, now, null)
            result.outcome == ChannelResult.Outcome.FAILED || made >= MAX_ATTEMPTS ->
                DeliveryOutcome(DeliveryState.FAILED, made, now, result.error)
            else -> DeliveryOutcome(DeliveryState.PENDING, made, now + BACKOFF[made - 1], result.error)
        }
    }
}
