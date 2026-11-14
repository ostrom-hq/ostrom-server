// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks

import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.runners.infrastructure.RunnersConfig
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class HooksModuleTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("hooks", HooksModule.name)
        assertEquals(1, HooksModule.migrations.size)
        koinApplication { modules(HooksModule.koinModule) }.close()
    }

    @Test
    fun itsFeatureNeedsNoConfiguration() = runTest {
        assertEquals(setOf("hooks.v1"), HooksModule.features(koinApplication {}.koin))
    }

    @Test
    fun cloudSessionsReceiveHooksOnlyWithCloudRunners() = runTest {
        suspend fun features(vararg values: Pair<String, String>): Set<String> {
            val config = RunnersConfig.from(MapConfigSource(values.toMap()))
            return HooksModule.features(koinApplication { modules(module { single { config } }) }.koin)
        }
        assertEquals(setOf("hooks.v1"), features())
        val cloud = features("OSTROM_RUNNERS_DRIVER" to "kubernetes", "OSTROM_RUNNERS_SERVER_URL" to "http://s")
        assertEquals(setOf("hooks.v1", "runners.cloud.hooks.v1"), cloud)
    }
}
