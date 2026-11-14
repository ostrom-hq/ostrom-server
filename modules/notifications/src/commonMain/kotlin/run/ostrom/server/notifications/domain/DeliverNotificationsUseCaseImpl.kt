// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/Notifications.kt; see NOTICE.
package run.ostrom.server.notifications.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.events.WaitSummaries
import run.ostrom.server.kernel.logging.Logger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [DeliverNotificationsUseCase] as TeamCode's: the fan-out reads each organization's page, the waits it names, the
 * members each channel reaches and the switches turned off, then queues one delivery per event, member and channel
 * with the cursor's move. The attempts send one batch per organization and channel, together, each under its
 * deadline: a slow provider delays neither the others nor its own backoff. One organization's failure is logged and
 * skipped, so it never holds the others' notifications back. A delivery about a wait answered or withdrawn since is
 * not sent (`wait_closed`).
 *
 * @param journal The organizations' event journal.
 * @param waits The waits the events name.
 * @param settings The members' switches.
 * @param deliveries The queue.
 * @param channels The channels this server offers.
 * @param clock The time.
 * @param logger Where an organization's failed pass is reported, by the error's type only.
 * @param newId Makes a delivery's id.
 */
@Suppress("LongParameterList") // The pass's collaborators, each a port.
class DeliverNotificationsUseCaseImpl(
    private val journal: OrgEventJournal,
    private val waits: WaitSummaries,
    private val settings: NotificationSettingsRepository,
    private val deliveries: NotificationDeliveriesRepository,
    private val channels: () -> List<NotificationChannel>,
    private val clock: Clock,
    private val logger: Logger,
    private val newId: () -> Uuid = Uuid::random,
) : DeliverNotificationsUseCase {
    override suspend fun invoke(): Int {
        // A failed fan-out still lets the queued deliveries go out.
        guarded(FANOUT_FAILED, Unit) { fanOut() }
        val now = clock.now()
        val due = mutableListOf<NotificationDelivery>()
        for (org in deliveries.workingOrgs(now, DeliveryRules.ORGS_PER_PASS)) {
            val room = DeliveryRules.BATCH - due.size
            if (room <= 0) break
            due += guarded(CLAIM_FAILED, emptyList()) { stillOpen(org, deliveries.claimDue(org, room, now, LEASE)) }
        }
        val offered = channels().associateBy { it.id }
        // The sends run together and only return results: the records follow, one failing record losing no other.
        val results = coroutineScope {
            due.groupBy { it.notification.orgId to it.channel }
                .map { (key, batch) -> async { attempt(offered[key.second], batch) } }
                .awaitAll()
                .flatten()
        }
        for ((delivery, result) in results) {
            guarded(RECORD_FAILED, Unit) { record(delivery, DeliveryRules.outcome(delivery.attempts, result, now())) }
        }
        return due.size
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
        val slice = journal.read(position, NotificationRules.SOURCE_TYPES, DeliveryRules.EVENTS_PER_FANOUT)
        val waitIds = slice.entries.filter { it.type == OrgEventType.WAIT_CREATED }
            .map(NotificationRules::waitIdOf).toSet()
        val known = if (waitIds.isEmpty()) emptyMap() else waits.of(orgId, waitIds).associateBy { it.id }
        val drafts = slice.entries.mapNotNull { entry ->
            NotificationRules.draft(NotificationSource(entry, known[NotificationRules.waitIdOf(entry)]))
        }
        val members = drafts.map { it.memberId }.toSet()
        if (members.isEmpty()) return Page(emptyList(), slice.through)
        val reachable = offered.associate { it.id to it.reachable(orgId, members) }
        val off = settings.disabled(orgId, members)
        val now = clock.now()
        val queued = drafts.flatMap { draft ->
            offered.map { it.id }
                .filter { draft.memberId in reachable.getValue(it) }
                .filter { SwitchedOff(draft.memberId, it, draft.kind) !in off }
                .map { channel -> NotificationDelivery(draft.toNotification(newId(), orgId, now), channel, 0) }
        }
        return Page(queued, slice.through)
    }

    /** Of an organization's claimed deliveries, those whose wait is still open; the others are failed, unsent. */
    private suspend fun stillOpen(orgId: Uuid, claimed: List<NotificationDelivery>): List<NotificationDelivery> {
        val waitIds = claimed.mapNotNull { it.notification.waitId }.toSet()
        if (waitIds.isEmpty()) return claimed
        val open = waits.of(orgId, waitIds).filter { it.open }.map { it.id }.toSet()
        val (send, closed) = claimed.partition { it.notification.waitId == null || it.notification.waitId in open }
        for (delivery in closed) {
            record(delivery, DeliveryOutcome(DeliveryState.FAILED, delivery.attempts, now(), WAIT_CLOSED))
        }
        return send
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

    private suspend fun record(delivery: NotificationDelivery, outcome: DeliveryOutcome) {
        val notification = delivery.notification
        deliveries.record(notification.orgId, notification.id, outcome, now())
    }

    private fun now() = clock.now()

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
    private class Page(val deliveries: List<NotificationDelivery>, val through: Long)

    private companion object {
        const val CHANNEL_UNAVAILABLE = "channel_unavailable"
        const val TIMEOUT = "timeout"
        const val NO_RESULT = "no_result"
        const val WAIT_CLOSED = "wait_closed"
        const val FANOUT_FAILED = "notification fan-out failed"
        const val CLAIM_FAILED = "notification claim failed"
        const val RECORD_FAILED = "notification record failed"
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
