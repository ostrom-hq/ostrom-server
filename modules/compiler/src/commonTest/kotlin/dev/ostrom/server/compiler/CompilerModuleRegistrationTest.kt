// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class CompilerModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("compiler", CompilerModule.name)
        koinApplication { modules(CompilerModule.koinModule) }.close()
    }
}
