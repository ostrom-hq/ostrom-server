// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions

import run.ostrom.server.kernel.sessions.LaunchLabels
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("sessions.v1", "sessions.activity.v1"), SessionsModule.features(koin))
    }

    @Test
    fun theLabelsOfASessionAreAnnouncedWhenTheLaunchesGiveThem() = runTest {
        val labels = module { single<LaunchLabels> { LaunchLabels { _, _ -> emptyMap() } } }
        val koin = koinApplication { modules(labels) }.koin
        assertEquals(setOf("sessions.v1", "sessions.activity.v1", "sessions.labels.v1"), SessionsModule.features(koin))
    }
}
