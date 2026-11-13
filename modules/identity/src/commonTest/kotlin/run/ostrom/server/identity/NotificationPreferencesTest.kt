// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.DEFAULT_NOTIFICATION_PREFERENCES
import run.ostrom.server.identity.domain.GetNotificationPreferencesUseCaseImpl
import run.ostrom.server.identity.domain.InvalidQuietHoursException
import run.ostrom.server.identity.domain.NotificationPreferences
import run.ostrom.server.identity.domain.QuietHours
import run.ostrom.server.identity.domain.SetNotificationPreferencesUseCaseImpl
import run.ostrom.server.identity.fakes.InMemoryNotificationPreferencesRepository
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.presentation.toBody
import run.ostrom.server.identity.presentation.toPreferences
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class NotificationPreferencesTest {
    private val stored = InMemoryNotificationPreferencesRepository()
    private val get = GetNotificationPreferencesUseCaseImpl(stored)
    private val set = SetNotificationPreferencesUseCaseImpl(stored, SettableClock())
    private val ada = Uuid.random()

    @Test
    fun aPersonWhoNeverChoseIsToldOfRequestsAndAnswersNotOfSpending() = runTest {
        assertEquals(NotificationPreferences(requests = true, decisions = true, spending = false), get(ada))
        assertEquals(DEFAULT_NOTIFICATION_PREFERENCES, get(ada))
    }

    @Test
    fun preferencesAreThePersonsOwnAndReplaceThePreviousOnes() = runTest {
        val night = QuietHours("22:00", "07:30", "Europe/Paris")
        val chosen = NotificationPreferences(requests = true, decisions = false, spending = true, quietHours = night)

        assertEquals(chosen, set(ada, chosen))
        assertEquals(chosen, get(ada))
        assertEquals(DEFAULT_NOTIFICATION_PREFERENCES, get(Uuid.random()), "Another person keeps the defaults")

        val noQuiet = chosen.copy(quietHours = null)
        set(ada, noQuiet)
        assertEquals(noQuiet, get(ada))
    }

    @Test
    fun quietHoursAreTwoDifferentClockTimesAndATimeZone() = runTest {
        listOf(
            QuietHours("00:00", "23:59", "UTC"),
            QuietHours("23:00", "06:00", "America/Argentina/Buenos_Aires"),
            QuietHours("12:00", "13:00", "Etc/GMT+5"),
        ).forEach { set(ada, DEFAULT_NOTIFICATION_PREFERENCES.copy(quietHours = it)) }

        listOf(
            QuietHours("24:00", "07:00", "UTC"),
            QuietHours("7:00", "08:00", "UTC"),
            QuietHours("22:00", "07:60", "UTC"),
            QuietHours("22:00", "22:00", "UTC"),
            QuietHours("22:00", "07:00", ""),
            QuietHours("22:00", "07:00", "Europe/Paris; DROP"),
            QuietHours("22:00", "07:00", "/Paris"),
            QuietHours("22:00", "07:00", "a/b/c/d"),
        ).forEach { quiet ->
            assertFailsWith<InvalidQuietHoursException>(quiet.toString()) {
                set(ada, DEFAULT_NOTIFICATION_PREFERENCES.copy(quietHours = quiet))
            }
        }
        assertTrue(stored.preferences.values.all { it.quietHours?.start != "24:00" })
    }

    @Test
    fun theBodyMapsBothWays() {
        val chosen = NotificationPreferences(true, false, true, QuietHours("22:00", "07:00", "UTC"))

        assertEquals(chosen, chosen.toBody().toPreferences())
        assertEquals(DEFAULT_NOTIFICATION_PREFERENCES, DEFAULT_NOTIFICATION_PREFERENCES.toBody().toPreferences())
    }
}
