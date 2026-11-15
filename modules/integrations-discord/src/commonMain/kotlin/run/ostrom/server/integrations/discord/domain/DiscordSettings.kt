// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.domain

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection

/**
 * The instance's Discord application (Admin → Settings → `discord`): its bot token and client secret, write-only
 * secrets sealed with the secrets key; its application id, public key and the instance's server (guild) id, plain.
 * Never an environment variable nor a Helm value. One application and one server per instance.
 */
object DiscordSettings {
    /** The section's name. */
    const val NAME: String = "discord"

    /** The bot's token (Bot → Token): sealed, never returned. */
    const val BOT_TOKEN: String = "botToken"

    /** The application id (General Information), the OAuth2 client id. */
    const val APPLICATION_ID: String = "applicationId"

    /** The application's Ed25519 public key (General Information), which checks every interaction. */
    const val PUBLIC_KEY: String = "publicKey"

    /** The OAuth2 client secret, for the link's code exchange: sealed, never returned. */
    const val CLIENT_SECRET: String = "clientSecret"

    /** The instance's Discord server, which the bot and every linked account are members of. */
    const val GUILD_ID: String = "guildId"

    /**
     * @param verify Asks Discord whether a bot token works (`GET /users/@me`): why not, or null when it does.
     *
     * @return The section the admin module serves and stores.
     */
    fun section(verify: suspend (String) -> String?): SettingsSection = SettingsSection(
        NAME,
        listOf(
            SettingsField(BOT_TOKEN, secret = true, verify = verify, check = ::botTokenRefusal),
            SettingsField(APPLICATION_ID) { snowflakeRefusal(it, "application id") },
            SettingsField(PUBLIC_KEY, check = ::publicKeyRefusal),
            SettingsField(CLIENT_SECRET, secret = true, check = ::clientSecretRefusal),
            SettingsField(GUILD_ID) { snowflakeRefusal(it, "server id") },
        ),
    )

    /**
     * @param value A bot token.
     *
     * @return Why it is refused, or null.
     */
    fun botTokenRefusal(value: String): String? =
        if (BOT_TOKEN_FORMAT.matches(value)) null else "Paste the bot's token (Bot, Reset Token)."

    /**
     * @param value An id.
     * @param what What it names.
     *
     * @return Why it is refused, or null.
     */
    fun snowflakeRefusal(value: String, what: String): String? =
        if (SNOWFLAKE.matches(value)) null else "A Discord $what is a number of 17 to 20 digits."

    /**
     * @param value A public key.
     *
     * @return Why it is refused, or null.
     */
    fun publicKeyRefusal(value: String): String? =
        if (PUBLIC_KEY_FORMAT.matches(value)) null else "The public key is 64 hexadecimal characters."

    /**
     * @param value A client secret.
     *
     * @return Why it is refused, or null.
     */
    fun clientSecretRefusal(value: String): String? =
        if (CLIENT_SECRET_FORMAT.matches(value)) null else "Paste the application's client secret (OAuth2)."

    private val BOT_TOKEN_FORMAT = Regex("[A-Za-z0-9_.-]{50,100}")
    private val SNOWFLAKE = Regex("[0-9]{17,20}")
    private val PUBLIC_KEY_FORMAT = Regex("[0-9a-fA-F]{64}")
    private val CLIENT_SECRET_FORMAT = Regex("[A-Za-z0-9_-]{16,128}")
}

/**
 * The stored Discord application, every field set.
 *
 * @property botToken The bot token.
 * @property applicationId The application id.
 * @property publicKey The public key, in hexadecimal.
 * @property clientSecret The client secret.
 * @property guildId The instance's server.
 */
class DiscordApp(
    val botToken: String,
    val applicationId: String,
    val publicKey: String,
    val clientSecret: String,
    val guildId: String,
) {
    override fun toString(): String = "DiscordApp($applicationId, ***)"
}

/**
 * Reads the Discord application at each use, so that a save applies at once on every replica.
 *
 * @param settings The instance's settings.
 */
class DiscordApps(private val settings: InstanceSettings) {
    /** @return The application, or null until all its fields are stored. */
    suspend fun current(): DiscordApp? {
        val values = settings.read(DiscordSettings.NAME)
        val botToken = values[DiscordSettings.BOT_TOKEN].orEmpty()
        val applicationId = values[DiscordSettings.APPLICATION_ID].orEmpty()
        val publicKey = values[DiscordSettings.PUBLIC_KEY].orEmpty()
        val clientSecret = values[DiscordSettings.CLIENT_SECRET].orEmpty()
        val guildId = values[DiscordSettings.GUILD_ID].orEmpty()
        val fields = listOf(botToken, applicationId, publicKey, clientSecret, guildId)
        if (fields.any { it.isBlank() }) return null
        return DiscordApp(botToken, applicationId, publicKey, clientSecret, guildId)
    }
}
