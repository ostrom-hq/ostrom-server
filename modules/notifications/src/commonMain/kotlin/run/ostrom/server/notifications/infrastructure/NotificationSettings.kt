// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.logging.LogLevel

/**
 * Where the notifications go, read once from the server's configuration.
 *
 * @property webOrigin The console's origin, in the e-mails' links (`OSTROM_WEB_ORIGIN`).
 * @property publicUrl The server's public URL (`OSTROM_PUBLIC_URL`), in each encrypted push: which account of the app
 *   it is for. Empty when unset.
 */
data class NotificationSettings(
    val webOrigin: String = DEFAULT_WEB_ORIGIN,
    val publicUrl: String = "",
) {
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
                webOrigin = (value("OSTROM_WEB_ORIGIN") ?: DEFAULT_WEB_ORIGIN).trimEnd('/'),
                publicUrl = value("OSTROM_PUBLIC_URL").orEmpty().trimEnd('/'),
            )
        }
    }
}

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
