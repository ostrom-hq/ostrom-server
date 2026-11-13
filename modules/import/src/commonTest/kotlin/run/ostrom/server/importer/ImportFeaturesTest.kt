// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("import.teamcode.v1"), ImportModule.features(koin))
    }
}
