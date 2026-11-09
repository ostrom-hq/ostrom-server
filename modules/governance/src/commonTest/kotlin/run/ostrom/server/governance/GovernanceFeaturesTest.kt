// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class GovernanceFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("governance.v1"), GovernanceModule.features(koin))
    }
}
