// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.artifacts.infrastructure.storage.S3ObjectStore
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArtifactsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("artifacts", ArtifactsModule.name)
        koinApplication { modules(ArtifactsModule.koinModule) }.close()
    }

    @Test
    fun theBucketStoreIsBuiltOnTheCheckedClientAndClosedWithIt() {
        val application = koinApplication { modules(ArtifactsModule.koinModule) }
        assertIs<S3ObjectStore>(application.koin.get<ArtifactObjectStore>())
        application.close()
    }
}
