// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RulesFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("rules.v1", "reports.v1"), RulesModule.features(koin))
    }
}
