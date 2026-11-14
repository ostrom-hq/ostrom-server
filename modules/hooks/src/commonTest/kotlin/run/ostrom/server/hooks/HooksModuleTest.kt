// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
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
}
