// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class BudgetsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("budgets", BudgetsModule.name)
        koinApplication { modules(BudgetsModule.koinModule) }.close()
    }
}
