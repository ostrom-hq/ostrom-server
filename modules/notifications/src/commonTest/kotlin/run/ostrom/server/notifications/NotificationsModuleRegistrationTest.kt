// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.identity.presentation.SignInConfig
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.notifications.infrastructure.NotificationsMigrations
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithItsMigrationAndDefinitionsThatLoad() {
        assertEquals("notifications", NotificationsModule.name)
        assertEquals(NotificationsMigrations, NotificationsModule.migrations)
        assertEquals(listOf(1L, 2L), NotificationsMigrations.map { it.version })
        koinApplication { modules(NotificationsModule.koinModule) }.close()
    }

    @Test
    fun anInstanceSendsEmailsWithResendOrAMailDirectoryOnly() {
        val none = SignInConfig.from(MapConfigSource(emptyMap()))
        assertFalse(none.copy(resendApiKey = null, mailDirectory = null).sendsMail())
        assertTrue(none.copy(resendApiKey = "re_key", mailDirectory = null).sendsMail())
        assertTrue(none.copy(resendApiKey = null, mailDirectory = "/var/lib/ostrom/mail").sendsMail())
    }
}
