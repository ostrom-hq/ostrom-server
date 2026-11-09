// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.slack

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackIntegrationModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("integrations-slack", SlackIntegrationModule.name)
        koinApplication { modules(SlackIntegrationModule.koinModule) }.close()
    }
}
