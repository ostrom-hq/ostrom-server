// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SecretsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("secrets", SecretsModule.name)
        koinApplication { modules(SecretsModule.koinModule) }.close()
    }
}
