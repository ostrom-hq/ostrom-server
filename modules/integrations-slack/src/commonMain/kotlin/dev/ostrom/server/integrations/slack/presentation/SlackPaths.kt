// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/webhooks/slack`: the clicks of Slack's buttons, signed with the app's signing secret. */
@Serializable
@Resource("/webhooks/slack")
class SlackInteractionsPath

/** `/api/v1/slack/links`: who of the organization is reached on Slack. */
@Serializable
@Resource("/api/v1/slack/links")
class SlackLinksPath {
    /**
     * One member's link.
     *
     * @property personId The member.
     */
    @Serializable
    @Resource("{personId}")
    class Person(val parent: SlackLinksPath = SlackLinksPath(), val personId: String)
}

/**
 * A member's Slack account.
 *
 * @property teamId The workspace (`T…`).
 * @property userId The member on Slack (`U…`).
 */
@Serializable
data class SlackLinkRequest(val teamId: String, val userId: String)

/**
 * A link, as the console shows it.
 *
 * @property personId The member.
 * @property teamId The workspace.
 * @property userId The member on Slack.
 * @property linkedBy Who linked them.
 * @property linkedAt When, ISO-8601.
 */
@Serializable
data class SlackLinkDto(
    val personId: String,
    val teamId: String,
    val userId: String,
    val linkedBy: String,
    val linkedAt: String,
)

/**
 * The organization's links.
 *
 * @property items The links.
 */
@Serializable
data class SlackLinksPage(val items: List<SlackLinkDto>)
