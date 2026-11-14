// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.notifications.infrastructure.NotificationSettings
import run.ostrom.server.notifications.infrastructure.startLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationSettingsTest {
    @Test
    fun anInstanceThatMailsLinksToTheDefaultOriginWarnsAtStart() {
        val default = NotificationSettings()
        val set = NotificationSettings(webOrigin = "https://console.ostrom.test")
        assertEquals(LogLevel.WARN, default.startLevel(mailing = true))
        assertEquals(LogLevel.INFO, default.startLevel(mailing = false))
        assertEquals(LogLevel.INFO, set.startLevel(mailing = true))
    }

    @Test
    fun theSettingsReadTheConsolesOriginAndThePublicUrl() {
        val defaults = NotificationSettings.from(config(emptyMap()))
        assertEquals(NotificationSettings(), defaults)
        assertEquals("http://localhost:5173", defaults.webOrigin)
        assertEquals("", defaults.publicUrl, "No public URL set")

        val set = NotificationSettings.from(
            config(
                mapOf(
                    "OSTROM_WEB_ORIGIN" to "https://console.ostrom.test/",
                    "OSTROM_PUBLIC_URL" to " https://ostrom.test/ ",
                ),
            ),
        )
        assertEquals(NotificationSettings("https://console.ostrom.test", "https://ostrom.test", true), set)
        val logger = RecordingLogger()
        set.warnDeprecated(logger)
        assertEquals(1, logger.events.size, "Only the former variable: read, and said deprecated")
        val current = NotificationSettings.from(
            config(
                mapOf("OSTROM_CONSOLE_URL" to "https://app.ostrom.test/", "OSTROM_WEB_ORIGIN" to "https://old.test"),
            ),
        )
        assertEquals(NotificationSettings("https://app.ostrom.test"), current, "OSTROM_CONSOLE_URL wins")
        current.warnDeprecated(logger)
        assertEquals(1, logger.events.size, "Nothing said with OSTROM_CONSOLE_URL")
    }

    private fun config(values: Map<String, String>) = object : ConfigSource {
        override fun get(key: String): String? = values[key]
    }
}
