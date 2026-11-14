// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger

/**
 * Where the notifications go, read once from the server's configuration.
 *
 * @property webOrigin The console's origin, in the e-mails' links (`OSTROM_CONSOLE_URL`, as every module reads it; the
 *   former `OSTROM_WEB_ORIGIN` while it alone is set).
 * @property publicUrl The server's public URL (`OSTROM_PUBLIC_URL`), in each encrypted push: which account of the app
 *   it is for. Empty when unset.
 * @property webOriginDeprecated Whether [webOrigin] came from the former `OSTROM_WEB_ORIGIN`.
 */
data class NotificationSettings(
    val webOrigin: String = DEFAULT_WEB_ORIGIN,
    val publicUrl: String = "",
    val webOriginDeprecated: Boolean = false,
) {
    /**
     * Says, once at start, that the console's address still comes from the former variable.
     *
     * @param logger Where it is said.
     */
    fun warnDeprecated(logger: Logger) {
        if (webOriginDeprecated) {
            logger.warn(
                "Deprecated variable: the console's address is OSTROM_CONSOLE_URL; remove OSTROM_WEB_ORIGIN.",
                mapOf("variable" to "OSTROM_WEB_ORIGIN"),
            )
        }
    }

    /** Reads the settings. */
    companion object {
        /**
         * @param config The server's configuration.
         *
         * @return The settings; a blank value counts as absent.
         */
        fun from(config: ConfigSource): NotificationSettings {
            fun value(key: String) = config[key].orEmpty().trim().ifEmpty { null }
            val console = value("OSTROM_CONSOLE_URL")
            val former = value("OSTROM_WEB_ORIGIN")
            return NotificationSettings(
                webOrigin = (console ?: former ?: DEFAULT_WEB_ORIGIN).trimEnd('/'),
                publicUrl = value("OSTROM_PUBLIC_URL").orEmpty().trimEnd('/'),
                webOriginDeprecated = console == null && former != null,
            )
        }
    }
}

private const val DEFAULT_WEB_ORIGIN = "http://localhost:5173"

/**
 * How loud the start of the notifications is: a warning when the instance sends e-mails while `OSTROM_CONSOLE_URL` is
 * left to its default, whose links (`http://localhost:5173/yes/…`) open nothing for an approver.
 *
 * @param mailing Whether the instance sends e-mails.
 *
 * @return [LogLevel.WARN] then, [LogLevel.INFO] otherwise.
 */
internal fun NotificationSettings.startLevel(mailing: Boolean): LogLevel =
    if (mailing && webOrigin == DEFAULT_WEB_ORIGIN) LogLevel.WARN else LogLevel.INFO
