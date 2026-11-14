// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.domain

import kotlinx.coroutines.CancellationException
import run.ostrom.core.api.models.SlackChannels
import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.services.GateChannel
import run.ostrom.server.kernel.events.GateSummary
import run.ostrom.server.notifications.domain.ApprovalChannel
import run.ostrom.server.notifications.domain.ApprovalDelivery
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationChannel
import kotlin.uuid.Uuid

/** What a Slack call's failure is for the queue: retried while Slack may still take it, failed for good otherwise. */
internal fun SlackCallException.result(): ChannelResult =
    if (retryable) ChannelResult.retry(code) else ChannelResult.failed(code)

private const val NOT_CONFIGURED = "slack_not_configured"
private const val NOT_LINKED = "slack_not_linked"

/**
 * The `slack` notification channel (docs/notifications.md): one direct message per notification to the Slack account
 * the member linked, through the router's queue and behind the member's switches like the push.
 *
 * @param apps The Slack app.
 * @param api Slack.
 * @param links The members' links.
 * @param texts The messages.
 */
class SlackNotificationChannel(
    private val apps: SlackApps,
    private val api: SlackApi,
    private val links: SlackLinksRepository,
    private val texts: SlackTexts,
) : NotificationChannel {
    override val id: String = SlackChannels.SLACK

    override suspend fun reachable(orgId: Uuid, memberIds: Set<Uuid>): Set<Uuid> {
        if (memberIds.isEmpty() || apps.current() == null) return emptySet()
        return links.ofMembers(orgId, memberIds).map { it.memberId }.toSet()
    }

    override suspend fun deliver(notifications: List<Notification>): Map<Uuid, ChannelResult> {
        val app = apps.current() ?: return notifications.associate { it.id to ChannelResult.failed(NOT_CONFIGURED) }
        return notifications.groupBy { it.orgId }.flatMap { (orgId, batch) ->
            val linked = links.ofMembers(orgId, batch.map { it.memberId }.toSet()).associateBy { it.memberId }
            batch.map { notification ->
                val link = linked[notification.memberId]
                notification.id to if (link == null) {
                    ChannelResult.failed(NOT_LINKED)
                } else {
                    send(app, link.slackUserId, texts.notification(notification))
                }
            }
        }.toMap()
    }

    private suspend fun send(app: SlackApp, userId: String, message: SlackMessage): ChannelResult = try {
        api.postMessage(app.botToken, userId, message)
        ChannelResult.DELIVERED
    } catch (error: SlackCallException) {
        error.result()
    }
}

/**
 * The yes requests in Slack: a direct message to the approver's linked account with Approve, a link to the console's
 * page of the request (a yes needs a fresh proof of who the approver is, SECURITY-AUDIT S2-54), and Reject, answered
 * at `/webhooks/slack`. The message is kept, so it loses its buttons once the gate is decided ([SlackGateChannel]).
 *
 * @param apps The Slack app.
 * @param api Slack.
 * @param links The members' links.
 * @param messages The posted messages.
 * @param texts The messages.
 */
class SlackApprovalChannel(
    private val apps: SlackApps,
    private val api: SlackApi,
    private val links: SlackLinksRepository,
    private val messages: SlackApprovalMessagesRepository,
    private val texts: SlackTexts,
) : ApprovalChannel {
    override val id: String = SlackChannels.SLACK

    override suspend fun reachable(orgId: Uuid, memberIds: Set<Uuid>): Set<Uuid> {
        if (memberIds.isEmpty() || apps.current() == null) return emptySet()
        return links.ofMembers(orgId, memberIds).map { it.memberId }.toSet()
    }

    override suspend fun send(delivery: ApprovalDelivery, gate: GateSummary, link: String): ChannelResult {
        val app = apps.current() ?: return ChannelResult.failed(NOT_CONFIGURED)
        val linked = links.of(delivery.orgId, delivery.memberId) ?: return ChannelResult.failed(NOT_LINKED)
        val message = texts.approval(delivery.orgId, delivery.approvalId, delivery.title, delivery.body, link)
        val posted = try {
            api.postMessage(app.botToken, linked.slackUserId, message)
        } catch (error: SlackCallException) {
            return error.result()
        }
        messages.save(delivery.orgId, SlackApprovalMessage(delivery.approvalId, delivery.gateId, posted))
        return ChannelResult.DELIVERED
    }
}

/**
 * Updates the yes requests' messages once their gate is decided, on any channel: the outcome, without the buttons.
 * Best effort: a failure never undoes the decision, and nothing is retried (the buttons of a decided gate do nothing).
 *
 * @param apps The Slack app.
 * @param api Slack.
 * @param messages The posted messages.
 * @param texts The messages.
 */
class SlackGateChannel(
    private val apps: SlackApps,
    private val api: SlackApi,
    private val messages: SlackApprovalMessagesRepository,
    private val texts: SlackTexts,
) : GateChannel {
    override val name: String = SlackChannels.SLACK

    // The request goes through the notifications' queue (SlackApprovalChannel).
    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) = Unit

    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) {
        try {
            val app = apps.current() ?: return
            val posted = messages.ofGate(gate.orgId, gate.id)
            if (posted.isEmpty()) return
            val message = texts.decided(gate.label.ifBlank { DEFAULT_TITLE }, outcome(gate))
            for (each in posted) {
                try {
                    api.updateMessage(app.botToken, each.posted, message)
                } catch (_: SlackCallException) {
                    Unit
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A failed read never reaches the answerer: the gate is decided either way.
            Unit
        }
    }

    private fun outcome(gate: Gate): String {
        val by = gate.decidedBy?.let { " by $it" }.orEmpty()
        return when (gate.status) {
            GateStatus.APPROVED, GateStatus.RESERVED, GateStatus.CONSUMED -> "Approved$by."
            GateStatus.REJECTED -> "Rejected$by."
            else -> "No longer waits for an answer."
        }
    }

    private companion object {
        const val DEFAULT_TITLE = "A request for a yes"
    }
}
