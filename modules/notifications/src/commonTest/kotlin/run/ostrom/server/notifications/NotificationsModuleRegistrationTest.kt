// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.notifications.domain.NotificationOutbox
import run.ostrom.server.notifications.infrastructure.NotificationSettings
import run.ostrom.server.notifications.infrastructure.NotificationsMigrations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithItsMigrationAndDefinitionsThatLoad() {
        assertEquals("notifications", NotificationsModule.name)
        assertEquals(NotificationsMigrations, NotificationsModule.migrations)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), NotificationsMigrations.map { it.version })
        koinApplication { modules(NotificationsModule.koinModule) }.close()
    }

    @Test
    fun theStartSaysWhetherEmailsGoNow() = runTest {
        assertEquals(listOf(LogLevel.WARN to true), started { true })
        assertEquals(listOf(LogLevel.INFO to false), started { false })
        assertEquals(listOf(LogLevel.INFO to false), started { error("database down") })
        assertEquals(emptyList(), started { throw CancellationException("stopping") })
    }

    private fun TestScope.started(
        mailing: suspend () -> Boolean,
    ): List<Pair<LogLevel, Any?>> {
        val logger = RecordingLogger()
        val app = koinApplication {
            modules(
                module {
                    single { NotificationOutbox(RecordingPushSender(), RecordingMailSender(), logger) }
                    single { NotificationSettings() }
                    single<MailAvailability> { MailAvailability { mailing() } }
                    single<Logger> { logger }
                },
            )
        }
        try {
            NotificationsModule.start(backgroundScope, app.koin)
            runCurrent()
        } finally {
            app.close()
        }
        return logger.events.filter { it.second.startsWith("approval notifications started") }
            .map { it.first to it.third["emails"] }
    }
}
