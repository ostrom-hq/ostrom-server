// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.domain

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection

/**
 * The instance's Slack app (Admin → Settings → `slack`): its bot token and its signing secret, both write-only secrets
 * sealed with the secrets key. Never an environment variable nor a Helm value. One Slack workspace per instance: the
 * workspace the app is installed in.
 */
object SlackSettings {
    /** The section's name. */
    const val NAME: String = "slack"

    /** The bot user's OAuth token, `xoxb-…`: sealed, never returned. */
    const val BOT_TOKEN: String = "botToken"

    /** The app's signing secret, which signs every request Slack sends to `/webhooks/slack`: sealed, never returned. */
    const val SIGNING_SECRET: String = "signingSecret"

    /**
     * @param verify Asks Slack whether a bot token works (`auth.test`): why not, or null when it does.
     *
     * @return The section the admin module serves and stores.
     */
    fun section(verify: suspend (String) -> String?): SettingsSection = SettingsSection(
        NAME,
        listOf(
            SettingsField(BOT_TOKEN, secret = true, verify = verify, check = ::botTokenRefusal),
            SettingsField(SIGNING_SECRET, secret = true, check = ::signingSecretRefusal),
        ),
    )

    /**
     * @param value A bot token.
     *
     * @return Why it is refused, or null.
     */
    fun botTokenRefusal(value: String): String? =
        if (BOT_TOKEN_FORMAT.matches(value)) null else "A Slack bot token starts with xoxb-."

    /**
     * @param value A signing secret.
     *
     * @return Why it is refused, or null.
     */
    fun signingSecretRefusal(value: String): String? =
        if (SIGNING_SECRET_FORMAT.matches(value)) null else "Paste the app's signing secret (Basic Information)."

    private val BOT_TOKEN_FORMAT = Regex("xoxb-[0-9A-Za-z-]{8,250}")
    private val SIGNING_SECRET_FORMAT = Regex("[0-9A-Za-z]{16,128}")
}

/**
 * The stored Slack app, both secrets set.
 *
 * @property botToken The bot token.
 * @property signingSecret The signing secret.
 */
class SlackApp(val botToken: String, val signingSecret: String) {
    override fun toString(): String = "SlackApp(***)"
}

/**
 * Reads the Slack app at each use, so that a save applies at once on every replica.
 *
 * @param settings The instance's settings.
 */
class SlackApps(private val settings: InstanceSettings) {
    /** @return The app, or null until both its secrets are stored. */
    suspend fun current(): SlackApp? {
        val values = settings.read(SlackSettings.NAME)
        val token = values[SlackSettings.BOT_TOKEN]?.takeIf { it.isNotBlank() }
        val secret = values[SlackSettings.SIGNING_SECRET]?.takeIf { it.isNotBlank() }
        return if (token != null && secret != null) SlackApp(token, secret) else null
    }
}
