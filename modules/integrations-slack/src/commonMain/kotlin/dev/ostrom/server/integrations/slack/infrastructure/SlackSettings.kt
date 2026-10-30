// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.infrastructure

import dev.ostrom.server.kernel.config.ConfigSource

/**
 * The server's Slack app.
 *
 * @property botToken `OSTROM_SLACK_BOT_TOKEN` (`xoxb-…`): without it, nothing is sent to Slack.
 * @property signingSecret `OSTROM_SLACK_SIGNING_SECRET`: without it, no interaction is ever accepted.
 * @property apiUrl `OSTROM_SLACK_API`, Slack's Web API by default.
 * @property webOrigin `OSTROM_WEB_ORIGIN`, the console the messages link to.
 */
data class SlackSettings(
    val botToken: String?,
    val signingSecret: String?,
    val apiUrl: String = "https://slack.com/api",
    val webOrigin: String = "http://localhost:5173",
) {
    // The token and the secret never appear in a log or a dump of the settings.
    override fun toString(): String =
        "SlackSettings(apiUrl=$apiUrl, bot=${botToken != null}, signing=${signingSecret != null})"

    /** Reads the settings. */
    companion object {
        /**
         * @param config The server's configuration.
         * @return The settings; a blank value counts as absent.
         */
        fun from(config: ConfigSource): SlackSettings {
            fun value(key: String) = config[key]?.trim()?.takeIf { it.isNotEmpty() }
            val defaults = SlackSettings(null, null)
            return SlackSettings(
                botToken = value("OSTROM_SLACK_BOT_TOKEN"),
                signingSecret = value("OSTROM_SLACK_SIGNING_SECRET"),
                apiUrl = (value("OSTROM_SLACK_API") ?: defaults.apiUrl).trimEnd('/'),
                webOrigin = (value("OSTROM_WEB_ORIGIN") ?: defaults.webOrigin).trimEnd('/'),
            )
        }
    }
}
