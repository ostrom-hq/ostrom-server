// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.domain

import kotlinx.coroutines.CancellationException
import run.ostrom.core.api.models.DiscordChannels
import run.ostrom.server.kernel.events.GateSummary
import run.ostrom.server.notifications.domain.ApprovalChannel
import run.ostrom.server.notifications.domain.ApprovalDelivery
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationChannel
import kotlin.uuid.Uuid

/** What a Discord call's failure is for the queue: retried while Discord may still take it, else failed for good. */
internal fun DiscordCallException.result(): ChannelResult =
    if (retryable) ChannelResult.retry(code) else ChannelResult.failed(code)

private const val NOT_CONFIGURED = "discord_not_configured"
private const val NOT_LINKED = "discord_not_linked"
private const val STORE_FAILED = "discord_store_failed"

/**
 * The direct message channel of a link: the one kept with it, or one opened now and kept (best effort: Discord
 * answers the same channel again).
 *
 * @param api Discord.
 * @param links The links.
 * @param app The application.
 * @param orgId The organization.
 * @param link The link.
 *
 * @return The channel id.
 * @throws DiscordCallException When Discord does not open it.
 */
internal suspend fun dmChannel(
    api: DiscordApi,
    links: DiscordLinksRepository,
    app: DiscordApp,
    orgId: Uuid,
    link: StoredDiscordLink,
): String {
    link.dmChannelId?.let { return it }
    val channel = api.openDm(app.botToken, link.discordUserId)
    try {
        links.saveDmChannel(orgId, link.memberId, link.discordUserId, channel)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Not kept: the next message opens it again, and Discord answers the same channel.
    }
    return channel
}

/**
 * The `discord` notification channel (docs/notifications.md): one direct message per notification to the Discord
 * account the member linked, through the router's queue and behind the member's switches like the push.
 *
 * @param apps The Discord application.
 * @param api Discord.
 * @param links The members' links.
 * @param texts The messages.
 */
class DiscordNotificationChannel(
    private val apps: DiscordApps,
    private val api: DiscordApi,
    private val links: DiscordLinksRepository,
    private val texts: DiscordTexts,
) : NotificationChannel {
    override val id: String = DiscordChannels.DISCORD

    override suspend fun reachable(orgId: Uuid, memberIds: Set<Uuid>): Set<Uuid> {
        if (memberIds.isEmpty() || apps.current() == null) return emptySet()
        return links.ofMembers(orgId, memberIds).map { it.memberId }.toSet()
    }

    override suspend fun deliver(notifications: List<Notification>): Map<Uuid, ChannelResult> {
        val app = apps.current() ?: return notifications.associate { it.id to ChannelResult.failed(NOT_CONFIGURED) }
        return notifications.groupBy { it.orgId }.flatMap { (orgId, batch) -> deliver(app, orgId, batch) }.toMap()
    }

    // One organization's batch: a read that fails retries this batch only, never another organization's.
    private suspend fun deliver(
        app: DiscordApp,
        orgId: Uuid,
        batch: List<Notification>,
    ): List<Pair<Uuid, ChannelResult>> {
        val linked = try {
            links.ofMembers(orgId, batch.map { it.memberId }.toSet()).associateBy { it.memberId }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return batch.map { it.id to ChannelResult.retry(STORE_FAILED) }
        }
        return batch.map { notification ->
            val link = linked[notification.memberId]
            notification.id to if (link == null) {
                ChannelResult.failed(NOT_LINKED)
            } else {
                send(app, orgId, link, texts.notification(notification))
            }
        }
    }

    private suspend fun send(app: DiscordApp, orgId: Uuid, link: StoredDiscordLink, message: DiscordMessage) = try {
        api.postMessage(app.botToken, dmChannel(api, links, app, orgId, link), message)
        ChannelResult.DELIVERED
    } catch (error: DiscordCallException) {
        error.result()
    }
}

/**
 * The yes requests in Discord: a direct message to the approver's linked account with Approve, a link button to the
 * console's page of the request (a yes needs a fresh proof of who the approver is, SECURITY-AUDIT S2-54), and Reject,
 * answered at `/webhooks/discord`. The message is kept, so it loses its buttons once the request is answered
 * ([decided], queued with the answer's event like the request itself).
 *
 * A request is told once: a message already kept for it was posted by an attempt whose outcome the queue lost, and
 * is never sent again; a message that could not be kept is taken back before the attempt is retried.
 *
 * @param apps The Discord application.
 * @param api Discord.
 * @param links The members' links.
 * @param messages The posted messages.
 * @param texts The messages.
 */
class DiscordApprovalChannel(
    private val apps: DiscordApps,
    private val api: DiscordApi,
    private val links: DiscordLinksRepository,
    private val messages: DiscordApprovalMessagesRepository,
    private val texts: DiscordTexts,
) : ApprovalChannel {
    override val id: String = DiscordChannels.DISCORD

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
            else -> post(app, linked, delivery, link)
        }
    }

    private suspend fun post(
        app: DiscordApp,
        linked: StoredDiscordLink,
        delivery: ApprovalDelivery,
        link: String,
    ): ChannelResult {
        val message = texts.approval(delivery.orgId, delivery.approvalId, delivery.title, delivery.body, link)
        val posted = try {
            api.postMessage(app.botToken, dmChannel(api, links, app, delivery.orgId, linked), message)
        } catch (error: DiscordCallException) {
            return error.result()
        }
        return keep(app, delivery, posted)
    }

    // The message is sent: a retry would send a second one while this one can never be closed. It is taken back when
    // Discord lets us (the retry then sends the only one); a delete Discord fails or asks to slow down on is tried
    // again here, a few times, since nothing keeps the message to delete it later. Past that, or on a refusal for good,
    // it stays and the request counts as told.
    private suspend fun keep(app: DiscordApp, delivery: ApprovalDelivery, posted: DiscordPosted): ChannelResult = try {
        messages.save(delivery.orgId, DiscordApprovalMessage(delivery.approvalId, delivery.gateId, posted))
        ChannelResult.DELIVERED
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        if (takeBack(app, posted)) ChannelResult.retry(NOT_KEPT) else ChannelResult.DELIVERED
    }

    // Whether the message is gone: deleted, or already not there; null while Discord fails the call.
    private suspend fun takeBack(app: DiscordApp, posted: DiscordPosted): Boolean {
        var gone: Boolean? = null
        var tries = 0
        while (gone == null && tries < DELETE_TRIES) {
            tries++
            gone = try {
                api.deleteMessage(app.botToken, posted)
                true
            } catch (failure: DiscordCallException) {
                when {
                    failure.code == GONE -> true
                    failure.retryable -> null
                    else -> false
                }
            }
        }
        return gone == true
    }

    /**
     * Closes the message kept for an answered request: the outcome, without the buttons. A request that was never told
     * on Discord has none, and is delivered at once.
     */
    @Suppress("ReturnCount") // Nothing kept, nothing configured, or the edit's own outcome.
    override suspend fun decided(delivery: ApprovalDelivery): ChannelResult {
        val kept = messages.of(delivery.orgId, delivery.approvalId) ?: return ChannelResult.DELIVERED
        val app = apps.current() ?: return ChannelResult.failed(NOT_CONFIGURED)
        return try {
            api.editMessage(app.botToken, kept.posted, texts.decided(delivery.title, outcome(delivery)))
            ChannelResult.DELIVERED
        } catch (error: DiscordCallException) {
            // A message the approver deleted has nothing left to close.
            if (error.code == GONE) ChannelResult.DELIVERED else error.result()
        }
    }

    private fun outcome(delivery: ApprovalDelivery): String = when (delivery.decision) {
        APPROVED -> "Approved by ${delivery.approver}."
        REJECTED -> "Rejected by ${delivery.approver}."
        else -> "No longer waits for an answer."
    }

    private companion object {
        const val NOT_KEPT = "discord_not_kept"

        /** Discord's JSON error 10008, Unknown Message. */
        const val GONE = "discord_10008"
        const val DELETE_TRIES = 3
        const val APPROVED = "approved"
        const val REJECTED = "rejected"
    }
}
