// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("automations", AutomationsModule.name)
        koinApplication { modules(AutomationsModule.koinModule) }.close()
    }
}
