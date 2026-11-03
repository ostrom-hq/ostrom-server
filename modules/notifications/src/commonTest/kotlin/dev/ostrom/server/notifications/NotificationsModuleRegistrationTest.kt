// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.notifications.infrastructure.NotificationsMigrations
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithItsMigrationAndDefinitionsThatLoad() {
        assertEquals("notifications", NotificationsModule.name)
        assertEquals(NotificationsMigrations, NotificationsModule.migrations)
        assertEquals(listOf(1L), NotificationsMigrations.map { it.version })
        koinApplication { modules(NotificationsModule.koinModule) }.close()
    }
}
