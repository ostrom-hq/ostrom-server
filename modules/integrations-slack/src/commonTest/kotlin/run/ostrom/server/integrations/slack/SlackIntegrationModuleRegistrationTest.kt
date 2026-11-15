// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import org.koin.dsl.koinApplication
import run.ostrom.server.integrations.slack.domain.SlackApi
import run.ostrom.server.integrations.slack.infrastructure.HttpSlackApi
import run.ostrom.server.integrations.slack.infrastructure.SlackMigrations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SlackIntegrationModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("integrations-slack", SlackIntegrationModule.name)
        assertEquals(SlackMigrations, SlackIntegrationModule.migrations)
        val app = koinApplication { modules(SlackIntegrationModule.koinModule) }
        // Slack's own API, on the module's outbound client: built without a call.
        assertIs<HttpSlackApi>(app.koin.get<SlackApi>())
        app.close()
    }
}
