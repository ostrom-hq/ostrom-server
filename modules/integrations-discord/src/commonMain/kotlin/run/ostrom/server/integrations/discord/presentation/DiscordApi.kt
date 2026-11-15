// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/v1/discord/link`: the caller's own Discord link (`getDiscordLink`, `linkDiscord`, `unlinkDiscord`). */
@Serializable
@Resource("/api/v1/discord/link")
class DiscordLinkPath {
    /**
     * `/api/v1/discord/link/callback` (`discordLinkCallback`): Discord's return, public, bound to its browser session.
     *
     * @property parent The link.
     * @property code Discord's code.
     * @property state The state of the start.
     * @property error Discord's error, when the person declined.
     */
    @Serializable
    @Resource("callback")
    class Callback(
        val parent: DiscordLinkPath = DiscordLinkPath(),
        val code: String? = null,
        val state: String? = null,
        val error: String? = null,
    )
}

/** `/webhooks/discord`: Discord's interactions, signed with the application's key (outside the API prefix). */
@Serializable
@Resource("/webhooks/discord")
class DiscordWebhookPath
