// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class GatesModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("gates", GatesModule.name)
        koinApplication { modules(GatesModule.koinModule) }.close()
    }
}
