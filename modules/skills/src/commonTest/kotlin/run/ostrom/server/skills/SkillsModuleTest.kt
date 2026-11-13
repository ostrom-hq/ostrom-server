// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
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
}
