// SPDX-License-Identifier: Apache-2.0
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
