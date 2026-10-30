// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.domain

import kotlinx.serialization.json.JsonArray
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A member of an organization reached on Slack: their requests come as a direct message, and a click on its buttons
 * answers as them. An owner or an admin of the organization sets it.
 *
 * @property orgId The organization.
 * @property personId The member, as the gates name approvers.
 * @property teamId The Slack workspace (`T…`).
 * @property userId The member on Slack (`U…`).
 * @property linkedBy Who set it.
 * @property linkedAt When.
 */
data class SlackLink(
    val orgId: Uuid,
    val personId: String,
    val teamId: String,
    val userId: String,
    val linkedBy: String,
    val linkedAt: Instant,
)

/**
 * A direct message sent for one approval request, updated once the gate is decided.
 *
 * @property orgId The organization.
 * @property requestId The request.
 * @property gateId Its gate.
 * @property channel The direct message channel (`D…`).
 * @property ts The message's timestamp, its id on Slack.
 */
data class SlackMessageRef(
    val orgId: Uuid,
    val requestId: Uuid,
    val gateId: Uuid,
    val channel: String,
    val ts: String,
)

/**
 * A Slack message: the text of the notification and its blocks.
 *
 * @property text The fallback text (notifications, screen readers).
 * @property blocks The Block Kit blocks.
 */
data class SlackText(val text: String, val blocks: JsonArray)

/**
 * A click on a button of a request's message, read from a verified interaction.
 *
 * @property teamId The workspace.
 * @property userId Who clicked.
 * @property yes Yes or no.
 * @property requestId The request the button carries.
 * @property shownDigest The digest of what the message showed: a yes on anything else is refused.
 * @property channel Where the message is.
 * @property ts The message.
 */
data class SlackClick(
    val teamId: String,
    val userId: String,
    val yes: Boolean,
    val requestId: Uuid,
    val shownDigest: String,
    val channel: String,
    val ts: String,
)
