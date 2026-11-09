// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnersModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("runners", RunnersModule.name)
        koinApplication { modules(RunnersModule.koinModule) }.close()
    }
}
