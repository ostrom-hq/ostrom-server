// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.logging.LogLevel

/**
 * Where the notifications go, read once from the server's configuration.
 *
 * @property expoPushUrl Expo's push endpoint (`OSTROM_EXPO_PUSH_URL`).
 * @property expoAccessToken Expo's access token, when the project's push security requires one
 *   (`OSTROM_EXPO_ACCESS_TOKEN`): never logged nor printed.
 * @property webOrigin The console's origin, in the e-mails' links (`OSTROM_WEB_ORIGIN`).
 */
data class NotificationSettings(
    val expoPushUrl: String = DEFAULT_EXPO_PUSH_URL,
    val expoAccessToken: String? = null,
    val webOrigin: String = DEFAULT_WEB_ORIGIN,
) {
    // The token never appears in a log or a dump of the settings.
    override fun toString(): String = "NotificationSettings(expoPushUrl=$expoPushUrl, " +
        "expoAccessToken=${if (expoAccessToken == null) "none" else "set"}, webOrigin=$webOrigin)"

    /** Reads the settings. */
    companion object {
        /**
         * @param config The server's configuration.
         *
         * @return The settings; a blank value counts as absent.
         */
        fun from(config: ConfigSource): NotificationSettings {
            fun value(key: String) = config[key].orEmpty().trim().ifEmpty { null }
            return NotificationSettings(
                expoPushUrl = value("OSTROM_EXPO_PUSH_URL") ?: DEFAULT_EXPO_PUSH_URL,
                expoAccessToken = value("OSTROM_EXPO_ACCESS_TOKEN"),
                webOrigin = (value("OSTROM_WEB_ORIGIN") ?: DEFAULT_WEB_ORIGIN).trimEnd('/'),
            )
        }
    }
}

private const val DEFAULT_EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send"
private const val DEFAULT_WEB_ORIGIN = "http://localhost:5173"

/**
 * How loud the start of the notifications is: a warning when the instance sends e-mails while `OSTROM_WEB_ORIGIN` is
 * left to its default, whose links (`http://localhost:5173/yes/…`) open nothing for an approver.
 *
 * @param mailing Whether the instance sends e-mails.
 *
 * @return [LogLevel.WARN] then, [LogLevel.INFO] otherwise.
 */
internal fun NotificationSettings.startLevel(mailing: Boolean): LogLevel =
    if (mailing && webOrigin == DEFAULT_WEB_ORIGIN) LogLevel.WARN else LogLevel.INFO
