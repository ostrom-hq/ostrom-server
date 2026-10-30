// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack

import dev.ostrom.server.integrations.slack.domain.SlackApi
import dev.ostrom.server.integrations.slack.infrastructure.HttpSlackApi
import dev.ostrom.server.integrations.slack.infrastructure.SlackSettings
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import io.ktor.client.HttpClient
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The module's registration: its name, its schema, and a Slack client built from the server's configuration. */
class SlackIntegrationModuleRegistrationTest {
    private fun app(values: Map<String, String>) = koinApplication {
        modules(SlackIntegrationModule.koinModule, module { single<ConfigSource> { MapConfigSource(values) } })
    }

    @Test
    fun registersUnderItsNameWithItsSchema() {
        assertEquals("integrations-slack", SlackIntegrationModule.name)
        assertEquals("create_slack", SlackIntegrationModule.migrations.single().name)
    }

    @Test
    fun theClientUsesTheConfiguredBotTokenOrStaysUnconfigured() {
        val configured = app(mapOf("OSTROM_SLACK_BOT_TOKEN" to "xoxb-1"))
        assertEquals("xoxb-1", configured.koin.get<SlackSettings>().botToken)
        assertTrue(assertIs<HttpSlackApi>(configured.koin.get<SlackApi>()).configured)
        configured.koin.get<HttpClient>(SLACK_HTTP)
        configured.close()
        val bare = app(emptyMap())
        assertFalse(bare.koin.get<SlackApi>().configured, "without a token Slack is never called")
        bare.close()
    }
}
