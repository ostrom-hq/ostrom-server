// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(
            setOf("automations.v1", "waits.v1", "waits.wake.v1", "waits.timed.v1"),
            AutomationsModule.features(koin),
        )
    }
}
