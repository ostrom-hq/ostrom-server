// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.domain

import kotlinx.coroutines.CancellationException
import run.ostrom.core.api.models.SlackChannels
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
 * at `/webhooks/slack`. The message is kept, so it loses its buttons once the request is answered ([decided], queued
 * with the answer's event like the request itself).
 *
 * A request is told once: a message already kept for it was posted by an attempt whose outcome the queue lost, and
 * is never sent again; a message that could not be kept is taken back before the attempt is retried.
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
        // Told by an earlier attempt, whose record the queue lost: a second message would keep the first's buttons.
        if (messages.of(delivery.orgId, delivery.approvalId) != null) return ChannelResult.DELIVERED
        val app = apps.current()
        val linked = app?.let { links.of(delivery.orgId, delivery.memberId) }
        return when {
            app == null -> ChannelResult.failed(NOT_CONFIGURED)
            linked == null -> ChannelResult.failed(NOT_LINKED)
            else -> post(app, linked.slackUserId, delivery, link)
        }
    }

    private suspend fun post(app: SlackApp, userId: String, delivery: ApprovalDelivery, link: String): ChannelResult {
        val message = texts.approval(delivery.orgId, delivery.approvalId, delivery.title, delivery.body, link)
        val posted = try {
            api.postMessage(app.botToken, userId, message)
        } catch (error: SlackCallException) {
            return error.result()
        }
        return keep(app, delivery, posted)
    }

    // The message is sent: a retry would send a second one while this one can never be closed. It is taken back when
    // Slack lets us (the retry then sends the only one). Slack failing or asking to slow down is retried too: the
    // message may still be there, and the request is not told while it cannot be closed. Only a refusal for good (the
    // bot may not delete it) leaves it: it stays and the request counts as told.
    private suspend fun keep(app: SlackApp, delivery: ApprovalDelivery, posted: SlackPosted): ChannelResult = try {
        messages.save(delivery.orgId, SlackApprovalMessage(delivery.approvalId, delivery.gateId, posted))
        ChannelResult.DELIVERED
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        try {
            api.deleteMessage(app.botToken, posted)
            ChannelResult.retry(NOT_KEPT)
        } catch (failure: SlackCallException) {
            when {
                // Already gone: the retry sends the only one.
                failure.code == GONE -> ChannelResult.retry(NOT_KEPT)
                failure.retryable -> ChannelResult.retry(NOT_TAKEN_BACK)
                else -> ChannelResult.DELIVERED
            }
        }
    }

    /**
     * Closes the message kept for an answered request: the outcome, without the buttons. A request that was never told
     * on Slack has none, and is delivered at once.
     */
    override suspend fun decided(delivery: ApprovalDelivery): ChannelResult {
        val kept = messages.of(delivery.orgId, delivery.approvalId) ?: return ChannelResult.DELIVERED
        return close(apps.current(), kept, delivery)
    }

    private suspend fun close(app: SlackApp?, kept: SlackApprovalMessage, delivery: ApprovalDelivery): ChannelResult {
        if (app == null) return ChannelResult.failed(NOT_CONFIGURED)
        return try {
            api.updateMessage(app.botToken, kept.posted, texts.decided(delivery.title, outcome(delivery)))
            ChannelResult.DELIVERED
        } catch (error: SlackCallException) {
            error.result()
        }
    }

    private fun outcome(delivery: ApprovalDelivery): String = when (delivery.decision) {
        APPROVED -> "Approved by ${delivery.approver}."
        REJECTED -> "Rejected by ${delivery.approver}."
        else -> "No longer waits for an answer."
    }

    private companion object {
        const val NOT_KEPT = "slack_not_kept"
        const val GONE = "message_not_found"
        const val NOT_TAKEN_BACK = "slack_not_taken_back"
        const val APPROVED = "approved"
        const val REJECTED = "rejected"
    }
}
