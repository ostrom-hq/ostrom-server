// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SharingModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("sharing", SharingModule.name)
        koinApplication { modules(SharingModule.koinModule) }.close()
    }
}
