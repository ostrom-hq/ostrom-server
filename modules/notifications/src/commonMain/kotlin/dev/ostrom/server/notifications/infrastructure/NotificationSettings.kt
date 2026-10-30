// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.infrastructure

import dev.ostrom.server.kernel.config.ConfigSource

/**
 * The notification services, read once from the server's configuration.
 *
 * @property expoUrl Expo's push endpoint (`OSTROM_EXPO_PUSH_URL`).
 * @property expoToken Expo's access token, when the project requires one (`OSTROM_EXPO_ACCESS_TOKEN`).
 * @property resendUrl Resend's API (`OSTROM_RESEND_API`).
 * @property resendKey Resend's API key (`OSTROM_RESEND_API_KEY`); without it no email is sent.
 * @property emailFrom The sender (`OSTROM_EMAIL_FROM`).
 * @property webOrigin The console's origin, in the links (`OSTROM_WEB_ORIGIN`).
 */
data class NotificationSettings(
    val expoUrl: String = "https://exp.host/--/api/v2/push/send",
    val expoToken: String? = null,
    val resendUrl: String = "https://api.resend.com",
    val resendKey: String? = null,
    val emailFrom: String = "Ostrom <notifications@ostrom.dev>",
    val webOrigin: String = "http://localhost:5173",
) {
    // The token and the key never appear in a log or a dump of the settings.
    override fun toString(): String =
        "NotificationSettings(expoUrl=$expoUrl, resendUrl=$resendUrl, expo=${expoToken != null}, " +
            "resend=${resendKey != null}, emailFrom=$emailFrom, webOrigin=$webOrigin)"

    /** Reads the settings. */
    companion object {
        /**
         * @param config The server's configuration.
         * @return The settings; a blank value counts as absent.
         */
        fun from(config: ConfigSource): NotificationSettings {
            fun value(key: String) = config[key]?.trim()?.takeIf { it.isNotEmpty() }
            val defaults = NotificationSettings()
            return NotificationSettings(
                expoUrl = value("OSTROM_EXPO_PUSH_URL") ?: defaults.expoUrl,
                expoToken = value("OSTROM_EXPO_ACCESS_TOKEN"),
                resendUrl = (value("OSTROM_RESEND_API") ?: defaults.resendUrl).trimEnd('/'),
                resendKey = value("OSTROM_RESEND_API_KEY"),
                emailFrom = value("OSTROM_EMAIL_FROM") ?: defaults.emailFrom,
                webOrigin = (value("OSTROM_WEB_ORIGIN") ?: defaults.webOrigin).trimEnd('/'),
            )
        }
    }
}
