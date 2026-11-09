// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class GatesFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("gates.v1"), GatesModule.features(koin))
    }
}
