// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class BudgetsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("budgets.v1"), BudgetsModule.features(koin))
    }
}
