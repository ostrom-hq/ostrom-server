// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class McpRelayFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("mcp.v1", "mcp.personal.v1"), McpRelayModule.features(koin))
    }
}
