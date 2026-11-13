// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets

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
