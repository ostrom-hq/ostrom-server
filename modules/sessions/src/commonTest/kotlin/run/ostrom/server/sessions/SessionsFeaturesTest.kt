// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("sessions.v1", "sessions.activity.v1"), SessionsModule.features(koin))
    }
}
