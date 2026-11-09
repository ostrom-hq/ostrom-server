// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("usage.v1"), UsageModule.features(koin))
    }
}
