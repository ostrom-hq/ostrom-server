// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SharingFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("sharing.v1", "sharing.handover.v1"), SharingModule.features(koin))
    }
}
