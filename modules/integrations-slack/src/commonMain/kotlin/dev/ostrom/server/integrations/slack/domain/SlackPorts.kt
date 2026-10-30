// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.uuid.Uuid

/** The Slack Web API calls the integration makes, with the bot token of the server's Slack app. */
interface SlackApi {
    /** Whether the server has a bot token: without one, nothing is sent. */
    val configured: Boolean

    /**
     * Opens (or finds) the direct message channel with a member.
     *
     * @param userId The member on Slack.
     * @return The channel.
     * @throws SlackCallException When Slack does not answer or refuses.
     */
    suspend fun openDirectMessage(userId: String): String

    /**
     * Posts a message.
     *
     * @param channel The channel.
     * @param message The message.
     * @return Its timestamp.
     * @throws SlackCallException When Slack does not answer or refuses.
     */
    suspend fun post(channel: String, message: SlackText): String

    /**
     * Replaces a message.
     *
     * @param channel The channel.
     * @param ts The message.
     * @param message Its new content.
     * @throws SlackCallException When Slack does not answer or refuses.
     */
    suspend fun update(channel: String, ts: String, message: SlackText)
}

/**
 * Slack did not answer, or refused a call.
 *
 * @param message What failed (never a token).
 * @param cause The transport or parsing error.
 */
class SlackCallException(message: String, override val cause: Throwable? = null) :
    OstromException(ErrorKind.UNAVAILABLE, message)

/** The integration's storage: who is who on Slack, and the messages sent per request. */
interface SlackRepository {
    /**
     * @param orgId The organization.
     * @return Its members reached on Slack.
     */
    suspend fun links(orgId: Uuid): List<SlackLink>

    /**
     * @param orgId The organization.
     * @param personId The member.
     * @return Their link, or null.
     */
    suspend fun link(orgId: Uuid, personId: String): SlackLink?

    /**
     * Finds who clicked, before any organization is known (the interaction's signature is checked first).
     *
     * @param teamId The workspace.
     * @param userId The member on Slack.
     * @return The link, or null.
     */
    suspend fun linkOf(teamId: String, userId: String): SlackLink?

    /**
     * Sets a member's link.
     *
     * @param link The link.
     * @return False when this Slack member is already linked to someone else (any organization).
     */
    suspend fun saveLink(link: SlackLink): Boolean

    /**
     * Removes a member's link.
     *
     * @param orgId The organization.
     * @param personId The member.
     * @return False when there was none.
     */
    suspend fun deleteLink(orgId: Uuid, personId: String): Boolean

    /**
     * Records a message sent for a request.
     *
     * @param message The message.
     */
    suspend fun saveMessage(message: SlackMessageRef)

    /**
     * @param orgId The organization.
     * @param gateId The gate.
     * @return The messages sent for its requests.
     */
    suspend fun messages(orgId: Uuid, gateId: Uuid): List<SlackMessageRef>
}
