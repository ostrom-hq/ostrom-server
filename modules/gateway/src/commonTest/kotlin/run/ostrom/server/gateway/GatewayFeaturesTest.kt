// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("inference.v1", "inference.queue.v1", "inference.cancel.v1"), GatewayModule.features(koin))
    }
}
