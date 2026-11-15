// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/Notifications.kt; see NOTICE.
package run.ostrom.server.notifications.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import run.ostrom.server.kernel.events.GateSummary
import run.ostrom.server.kernel.events.JournalEntry
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.events.SessionNames
import run.ostrom.server.kernel.events.WaitSummaries
import run.ostrom.server.kernel.logging.Logger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [DeliverNotificationsUseCase] as TeamCode's: the fan-out reads each organization's page, the waits it names, the
 * names of their sessions (a notification's title), the members each channel reaches and the switches turned off, then
 * queues one delivery per event, member and channel with the cursor's move. The attempts send one batch per
 * organization and channel, together, each under its deadline: a slow provider delays neither the others nor its own
 * backoff. One organization's failure is logged and skipped, so it never holds the others' notifications back. A
 * delivery about a wait answered or withdrawn since is not sent (`wait_closed`). Each organization with deliveries due
 * is given its share of the pass's batch before any takes more (TeamCode claims the batch across teams, the oldest due
 * first: one team's burst there delays the others). The same pass routes the requests for a yes ([ApprovalRouter]):
 * queued with the page, attempted with the batch, and not sent once their gate is decided or their approver no longer
 * asked (`gate_decided`).
 *
 * @param journal The organizations' event journal.
 * @param waits The waits the events name.
 * @param sessions The names of the sessions the waits belong to; null when no session has a name.
 * @param settings The members' switches.
 * @param deliveries The queue.
 * @param channels The channels this server offers.
 * @param approvals The approvals' half of the router.
 * @param clock The time.
 * @param logger Where an organization's failed pass is reported, by the error's type only.
 * @param newId Makes a delivery's id.
 */
@Suppress("LongParameterList") // The pass's collaborators, each a port.
class DeliverNotificationsUseCaseImpl(
    private val journal: OrgEventJournal,
    private val waits: WaitSummaries,
    private val sessions: SessionNames?,
    private val settings: NotificationSettingsRepository,
    private val deliveries: NotificationDeliveriesRepository,
    private val channels: () -> List<NotificationChannel>,
    private val approvals: ApprovalRouter,
    private val clock: Clock,
    private val logger: Logger,
    private val newId: () -> Uuid = Uuid::random,
) : DeliverNotificationsUseCase {
    override suspend fun invoke(): Int {
        // A failed fan-out still lets the queued deliveries go out.
        guarded(FANOUT_FAILED, Unit) { fanOut() }
        val now = clock.now()
        val due = mutableListOf<NotificationDelivery>()
        val asks = mutableListOf<Pair<ApprovalDelivery, GateSummary?>>()
        // The rows claimed, sent or not (one about a closed wait is failed unsent): the batch bounds the claims.
        var taken = 0
        suspend fun claim(org: Uuid, limit: Int): Boolean = guarded(CLAIM_FAILED, false) {
            val claimed = deliveries.claimDue(org, limit, now, LEASE)
            taken += claimed.size
            due += stillOpen(org, claimed.filterIsInstance<NotificationDelivery>())
            // An answer is sent whatever its gate: the message it updates was told while the gate was open.
            val (answers, requests) = claimed.filterIsInstance<ApprovalDelivery>().partition { it.decision != null }
            asks += stillAsked(org, requests)
            asks += unlessRequestPending(org, answers).map { it to null }
            claimed.size >= limit
        }
        val orgs = deliveries.workingOrgs(now, DeliveryRules.ORGS_PER_PASS)
        // Each organization's share first, the longest waiting first, so one organization's burst never fills the
        // batch while others wait; the room left then goes to those that filled their share, in the same order.
        val share = DeliveryRules.fairShare(orgs.size)
        val more = mutableListOf<Uuid>()
        for (org in orgs) {
            val room = DeliveryRules.BATCH - taken
            if (room <= 0) break
            if (claim(org, minOf(share, room))) more += org
        }
        for (org in more) {
            val room = DeliveryRules.BATCH - taken
            if (room <= 0) break
            claim(org, room)
        }
        val offered = channels().associateBy { it.id }
        // The sends run together and only return results: the records follow, one failing record losing no other.
        val results = coroutineScope {
            val batches = due.groupBy { it.orgId to it.channel }
                .map { (key, batch) -> async { attempt(offered[key.second], batch) } }
            val asked = asks.map { (delivery, gate) ->
                async { listOf(delivery to approvals.send(delivery, gate)) }
            }
            (batches + asked).awaitAll().flatten()
        }
        for ((delivery, result) in results) {
            val outcome = DeliveryRules.outcome(delivery.attempts, result, clock.now())
            // An approver not told is an operator's concern, as the in-memory outbox logged it: our codes only.
            if (delivery is ApprovalDelivery && outcome.state != DeliveryState.DELIVERED) {
                logger.warn(
                    APPROVAL_NOT_SENT,
                    mapOf(
                        "gate_id" to delivery.gateId,
                        "channel" to delivery.channel,
                        "attempt" to outcome.attempts,
                        "state" to outcome.state.wire,
                        "error" to outcome.error,
                    ),
                )
            }
            guarded(RECORD_FAILED, Unit) { record(delivery, outcome) }
        }
        return due.size + asks.size
    }

    override suspend fun fanOut(): Int {
        val offered = channels()
        // Without a channel, the journal waits for one rather than being read past (TeamCode).
        if (offered.isEmpty()) return 0
        var queued = 0
        for (position in journal.behind(DeliveryRules.READER, DeliveryRules.ORGS_PER_PASS)) {
            // One organization's failure delays neither the others nor the attempts.
            queued += guarded(FANOUT_FAILED, 0) {
                val page = page(position, offered)
                if (deliveries.enqueue(position, page.through, page.deliveries)) page.deliveries.size else 0
            }
        }
        return queued
    }

    override suspend fun maintain(): Long {
        val now = clock.now()
        return deliveries.maintain(
            now,
            now - DeliveryRules.GIVE_UP,
            now - DeliveryRules.RETENTION,
            DeliveryRules.MAINTAIN_BATCH,
        )
    }

    /** The deliveries one page of [position]'s journal makes, and where the cursor moves. */
    private suspend fun page(
        position: JournalPosition,
        offered: List<NotificationChannel>,
    ): Page {
        val orgId = position.orgId
        val slice = journal.read(position, DeliveryRules.SOURCE_TYPES, DeliveryRules.EVENTS_PER_FANOUT)
        val now = clock.now()
        val told = notifications(orgId, slice.entries.filter { it.type == OrgEventType.WAIT_CREATED }, offered, now)
        // The approvals' rows follow the members' notifications; no switch of the router applies to them.
        return Page(told + approvals.queue(orgId, slice.entries, now, newId), slice.through)
    }

    /** The members' notifications [entries] make, on each channel that reaches them and they left on. */
    private suspend fun notifications(
        orgId: Uuid,
        entries: List<JournalEntry>,
        offered: List<NotificationChannel>,
        now: Instant,
    ): List<NotificationDelivery> {
        val waitIds = entries.map(NotificationRules::waitIdOf).toSet()
        val known = if (waitIds.isEmpty()) emptyMap() else waits.of(orgId, waitIds).associateBy { it.id }
        // Only the waits that tell someone need their session's name, read once for the page (TeamCode).
        val sessionIds = known.values.filter { it.open }.mapNotNull { it.sessionId }.toSet()
        // A title is only words: a failed read of the names keeps the default title, it never holds the page back.
        val names = if (sessions == null || sessionIds.isEmpty()) {
            emptyMap()
        } else {
            guarded(NAMES_FAILED, emptyMap()) { sessions.of(orgId, sessionIds) }
        }
        val drafts = entries.mapNotNull { entry ->
            val wait = known[NotificationRules.waitIdOf(entry)]
            NotificationRules.draft(NotificationSource(entry, wait, wait?.sessionId?.let(names::get)))
        }
        val members = drafts.map { it.memberId }.toSet()
        if (members.isEmpty()) return emptyList()
        val reachable = offered.associate { it.id to it.reachable(orgId, members) }
        val off = settings.disabled(orgId, members)
        return drafts.flatMap { draft ->
            offered.map { it.id }
                .filter { draft.memberId in reachable.getValue(it) }
                .filter { SwitchedOff(draft.memberId, it, draft.kind) !in off }
                .map { channel -> NotificationDelivery(draft.toNotification(newId(), orgId, now), channel, 0) }
        }
    }

    /** Of an organization's claimed deliveries, those whose wait is still open; the others are failed, unsent. */
    private suspend fun stillOpen(orgId: Uuid, claimed: List<NotificationDelivery>): List<NotificationDelivery> {
        val waitIds = claimed.mapNotNull { it.notification.waitId }.toSet()
        if (waitIds.isEmpty()) return claimed
        val open = waits.of(orgId, waitIds).filter { it.open }.map { it.id }.toSet()
        val (send, closed) = claimed.partition { it.notification.waitId == null || it.notification.waitId in open }
        for (delivery in closed) {
            record(delivery, DeliveryOutcome(DeliveryState.FAILED, delivery.attempts, clock.now(), WAIT_CLOSED))
        }
        return send
    }

    /** Of an organization's claimed approvals, those whose approver is still asked; the others are failed, unsent. */
    private suspend fun stillAsked(
        orgId: Uuid,
        claimed: List<ApprovalDelivery>,
    ): List<Pair<ApprovalDelivery, GateSummary>> {
        val asked = approvals.stillAsked(orgId, claimed)
        return claimed.mapNotNull { delivery ->
            val gate = asked[delivery.id]
            if (gate == null) {
                record(delivery, DeliveryOutcome(DeliveryState.FAILED, delivery.attempts, clock.now(), GATE_DECIDED))
            }
            gate?.let { delivery to it }
        }
    }

    /**
     * Of an organization's claimed answers, those whose request is not still to be told on the same channel: an answer
     * that ran with its request (the same pass, or a slower attempt) would find no message to close and end, the
     * message then posted with its buttons. The others wait for the next attempt, which counts like any retry.
     */
    private suspend fun unlessRequestPending(orgId: Uuid, answers: List<ApprovalDelivery>): List<ApprovalDelivery> {
        if (answers.isEmpty()) return answers
        val pending = deliveries.pendingRequests(orgId, answers.map { it.approvalId }.toSet())
        val (waiting, ready) = answers.partition { it.approvalId to it.channel in pending }
        for (delivery in waiting) {
            val outcome = DeliveryRules.outcome(delivery.attempts, ChannelResult.retry(REQUEST_PENDING), clock.now())
            record(delivery, outcome)
        }
        return ready
    }

    /** Sends one organization's batch on one channel; never throws but for cancellation. */
    private suspend fun attempt(
        channel: NotificationChannel?,
        batch: List<NotificationDelivery>,
    ): List<Pair<NotificationDelivery, ChannelResult>> {
        if (channel == null) return batch.map { it to ChannelResult.failed(CHANNEL_UNAVAILABLE) }
        val results = try {
            withTimeoutOrNull(DeliveryRules.SEND_DEADLINE) { channel.deliver(batch.map { it.notification }) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A channel's own failure is retried like an unreachable provider; its message is never kept.
            emptyMap()
        }
        return batch.map { delivery ->
            delivery to when (results) {
                null -> ChannelResult.retry(TIMEOUT)
                else -> results[delivery.notification.id] ?: ChannelResult.retry(NO_RESULT)
            }
        }
    }

    private suspend fun record(delivery: QueuedDelivery, outcome: DeliveryOutcome) {
        deliveries.record(delivery.orgId, delivery.id, outcome, clock.now())
    }

    /** Runs [block]; a failure is logged by its type only (a database error quotes row values) and gives [fallback]. */
    private suspend fun <T> guarded(message: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.warn(message, mapOf("error" to error::class.simpleName))
        fallback
    }

    /** The deliveries a page makes, and where the cursor moves once they are queued. */
    private class Page(val deliveries: List<QueuedDelivery>, val through: Long)

    private companion object {
        const val CHANNEL_UNAVAILABLE = "channel_unavailable"
        const val TIMEOUT = "timeout"
        const val NO_RESULT = "no_result"
        const val WAIT_CLOSED = "wait_closed"
        const val GATE_DECIDED = "gate_decided"
        const val REQUEST_PENDING = "request_pending"
        const val FANOUT_FAILED = "notification fan-out failed"
        const val NAMES_FAILED = "notification session names read failed"
        const val CLAIM_FAILED = "notification claim failed"
        const val RECORD_FAILED = "notification record failed"
        const val APPROVAL_NOT_SENT = "approval notification not sent"
        val LEASE = DeliveryRules.LEASE
    }
}

private fun NotificationDraft.toNotification(id: Uuid, orgId: Uuid, now: Instant) = Notification(
    id = id,
    orgId = orgId,
    memberId = memberId,
    kind = kind,
    title = title,
    body = body,
    sessionId = sessionId,
    waitId = waitId,
    collapseKey = collapseKey,
    createdAt = now,
    eventSeq = eventSeq,
)
