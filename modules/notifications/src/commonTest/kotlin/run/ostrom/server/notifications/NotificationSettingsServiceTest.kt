// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import kotlinx.coroutines.test.runTest
import run.ostrom.server.notifications.domain.InvalidNotificationSettingException
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.NotificationSetting
import run.ostrom.server.notifications.domain.NotificationSettingsRepository
import run.ostrom.server.notifications.domain.NotificationSettingsServiceImpl
import run.ostrom.server.notifications.domain.SwitchedOff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/** TeamCode's `NotificationService`: defaults filled, a channel the server does not offer refused. */
class NotificationSettingsServiceTest {
    private val org = Uuid.random()
    private val mia = Uuid.random()

    private class MemoryRepository : NotificationSettingsRepository {
        val stored = mutableMapOf<Pair<Uuid, Pair<String, NotificationKind>>, Boolean>()

        override suspend fun settings(orgId: Uuid, memberId: Uuid): List<NotificationSetting> =
            stored.filterKeys { it.first == memberId }.map { (key, on) ->
                NotificationSetting(key.second.first, key.second.second, on)
            }

        override suspend fun set(orgId: Uuid, memberId: Uuid, setting: NotificationSetting) {
            stored[memberId to (setting.channel to setting.kind)] = setting.enabled
        }

        override suspend fun disabled(orgId: Uuid, memberIds: Set<Uuid>): Set<SwitchedOff> = emptySet()

        override suspend fun deleteAll(orgId: Uuid) = stored.clear()
    }

    @Test
    fun aServerWithoutChannelsOffersNoSwitchAndRefusesEveryChange() = runTest {
        val service = NotificationSettingsServiceImpl(MemoryRepository()) { emptyList() }
        assertEquals(emptyList(), service.settings(org, mia).settings)
        assertFailsWith<InvalidNotificationSettingException> { service.set(org, mia, "push", "waiting", false) }
    }

    @Test
    fun aStoredSwitchReplacesItsDefault() = runTest {
        val repository = MemoryRepository()
        val service = NotificationSettingsServiceImpl(repository) { listOf("push") }
        val changed = service.set(org, mia, "push", "session_failed", false)
        assertEquals(listOf("push"), changed.channels)
        assertEquals(
            NotificationKind.entries.map { NotificationSetting("push", it, it != NotificationKind.SESSION_FAILED) },
            changed.settings,
        )
        assertFailsWith<InvalidNotificationSettingException> { service.set(org, mia, "push", "later", false) }
        repository.deleteAll(org)
    }
}
