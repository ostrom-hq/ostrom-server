// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.kernel.events.GateSummaries
import run.ostrom.server.kernel.events.GateSummary
import run.ostrom.server.kernel.events.JournalEntry
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The approvals' half of the router: the requests for a yes a page of the journal names become deliveries, one per
 * approver still asked and way to reach them ([ApprovalRules]); at each attempt the gate is read again, and a
 * delivery whose gate was decided since, or whose approver is no longer asked, is not sent. The members' switches of
 * the router never apply: the approver's `requests` preference and the instance's mail do.
 *
 * @param gates The gates the events name.
 * @param approvers Finds the approvers among the members.
 * @param mailing Whether the instance sends e-mails now.
 * @param sender Sends one delivery.
 */
class ApprovalRouter(
    private val gates: GateSummaries,
    private val approvers: Approvers,
    private val mailing: MailAvailability,
    private val sender: ApprovalSender,
) {
    /**
     * @param orgId The organization of the page.
     * @param entries The page's events, of any type.
     * @param now The time.
     * @param newId Makes a delivery's id.
     *
     * @return The deliveries the page's approval requests make.
     */
    suspend fun queue(
        orgId: Uuid,
        entries: List<JournalEntry>,
        now: Instant,
        newId: () -> Uuid,
    ): List<ApprovalDelivery> {
        val events = entries.mapNotNull(ApprovalRules::event)
        val gateIds = events.map { it.gateId.toString() }.toSet()
        val known = if (gateIds.isEmpty()) emptyMap() else gates.of(orgId, gateIds).associateBy { it.id }
        val asked = events.mapNotNull { event ->
            known[event.gateId.toString()]?.takeIf { ApprovalRules.asks(it, event.approver) }?.let { event to it }
        }
        if (asked.isEmpty()) return emptyList()
        val reached = approvers.reach(orgId, asked.map { it.first.approver }.toSet())
        // Read once for the page: the same answer for every approver.
        val mails = mailing.available()
        return asked.flatMap { (event, gate) ->
            val approver = reached[event.approver] ?: return@flatMap emptyList()
            ApprovalRules.channels(approver, mails).map { channel ->
                ApprovalDelivery(
                    id = newId(),
                    orgId = orgId,
                    memberId = approver.memberId,
                    approver = event.approver,
                    approvalId = event.approvalId,
                    gateId = event.gateId,
                    channel = channel,
                    attempts = 0,
                    eventSeq = event.eventSeq,
                    title = ApprovalNotices.title(gate),
                    body = ApprovalNotices.body(gate),
                    createdAt = now,
                )
            }
        }
    }

    /**
     * @param orgId The organization.
     * @param claimed Its claimed approval deliveries.
     *
     * @return Each delivery whose approver the gate still asks, with the gate as it is now; the others are absent.
     */
    suspend fun stillAsked(orgId: Uuid, claimed: List<ApprovalDelivery>): Map<Uuid, GateSummary> {
        if (claimed.isEmpty()) return emptyMap()
        val known = gates.of(orgId, claimed.map { it.gateId.toString() }.toSet()).associateBy { it.id }
        return claimed.mapNotNull { delivery ->
            known[delivery.gateId.toString()]?.takeIf { ApprovalRules.asks(it, delivery.approver) }
                ?.let { delivery.id to it }
        }.toMap()
    }

    /**
     * Sends one delivery within [DeliveryRules.SEND_DEADLINE].
     *
     * @param delivery The delivery.
     * @param gate Its gate, as [stillAsked] read it.
     *
     * @return What the attempt produced: a timeout or a failed read is retried; never throws but for cancellation.
     */
    suspend fun send(delivery: ApprovalDelivery, gate: GateSummary): ChannelResult = try {
        withTimeoutOrNull(DeliveryRules.SEND_DEADLINE) { sender.send(delivery, gate) } ?: ChannelResult.retry(TIMEOUT)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // A failed read (the approver, their phones) is retried like an unreachable provider; its message never kept.
        ChannelResult.retry(NO_RESULT)
    }

    private companion object {
        const val TIMEOUT = "timeout"
        const val NO_RESULT = "no_result"
    }
}
