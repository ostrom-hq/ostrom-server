// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.notifications

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("notifications.v1", "notifications.push.v1"), NotificationsModule.features(koin))
    }
}
