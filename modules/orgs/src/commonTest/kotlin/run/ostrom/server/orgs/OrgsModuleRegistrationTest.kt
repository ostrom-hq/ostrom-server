// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import kotlin.test.Test
import kotlin.test.assertEquals
import org.koin.dsl.koinApplication

class OrgsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("orgs", OrgsModule.name)
        koinApplication { modules(OrgsModule.koinModule) }.close()
    }
}
