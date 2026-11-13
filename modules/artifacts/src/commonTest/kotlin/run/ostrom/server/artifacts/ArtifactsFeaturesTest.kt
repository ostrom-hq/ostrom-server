// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtifactsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("artifacts.v1"), ArtifactsModule.features(koin))
    }
}
