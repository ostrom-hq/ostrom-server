// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtifactsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("artifacts", ArtifactsModule.name)
        koinApplication { modules(ArtifactsModule.koinModule) }.close()
    }
}
