// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills

import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.runners.infrastructure.RunnersConfig
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillsModuleTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("skills", SkillsModule.name)
        assertEquals(1, SkillsModule.migrations.size)
        koinApplication { modules(SkillsModule.koinModule) }.close()
    }

    @Test
    fun itsFeatureNeedsNoConfiguration() = runTest {
        assertEquals(setOf("skills.v1"), SkillsModule.features(koinApplication {}.koin))
    }

    @Test
    fun cloudSessionsReceiveSkillsOnlyWithCloudRunners() = runTest {
        suspend fun features(vararg values: Pair<String, String>): Set<String> {
            val config = RunnersConfig.from(MapConfigSource(values.toMap()))
            return SkillsModule.features(koinApplication { modules(module { single { config } }) }.koin)
        }
        assertEquals(setOf("skills.v1"), features())
        val cloud = features("OSTROM_RUNNERS_DRIVER" to "kubernetes", "OSTROM_RUNNERS_SERVER_URL" to "http://s")
        assertEquals(setOf("skills.v1", "runners.cloud.skills.v1"), cloud)
    }
}
