// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class McpRelayFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("mcp.v1"), McpRelayModule.features(koin))
    }
}
