// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.notifications.domain.DeletePushDeviceUseCaseImpl
import dev.ostrom.server.notifications.domain.InvalidPushDeviceException
import dev.ostrom.server.notifications.domain.MAX_PHONES
import dev.ostrom.server.notifications.domain.PushDeviceNotFoundException
import dev.ostrom.server.notifications.domain.PushRegistration
import dev.ostrom.server.notifications.domain.RegisterPushDeviceUseCaseImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PushDevicesUseCasesTest {
    private val repository = InMemoryPushDevicesRepository()
    private val clock = TestClock()
    private val register = RegisterPushDeviceUseCaseImpl(repository, clock)
    private val remove = DeletePushDeviceUseCaseImpl(repository)
    private val org = Uuid.random()
    private val member = Uuid.random()

    @Test
    fun aPhoneIsRegisteredWithItsTrimmedTokenItsLanguageAndTheTimeAndItsMemberKeepsTheirLatestTen() = runTest {
        val registration = PushRegistration("  ExponentPushToken[abcdefgh-12_3]  ", "expo", "ios", " fr-FR ")
        val device = register(org, member, registration)

        assertEquals("ExponentPushToken[abcdefgh-12_3]", device.pushToken)
        assertEquals(Triple("expo", "ios", "fr-FR"), Triple(device.provider, device.platform, device.locale))
        assertEquals(org to member, device.orgId to device.memberId)
        assertEquals(clock.now to clock.now, device.createdAt to device.seenAt)
        assertEquals(listOf(device), repository.devices)
        assertEquals(10, MAX_PHONES)
        assertEquals(MAX_PHONES, repository.kept)
    }

    @Test
    fun aPhoneWithoutALanguageOrWithABlankOneIsRegisteredWithNone() = runTest {
        val none = register(org, member, PushRegistration("ExpoPushToken[abcdefgh]", "expo", "android", null))
        val blank = register(org, member, PushRegistration("ExpoPushToken[ijklmnop]", "expo", "android", "  "))

        assertNull(none.locale)
        assertNull(blank.locale)
        assertEquals("android", none.platform)
    }

    @Test
    fun anythingButAnExpoTokenOfAnIosOrAndroidPhoneInALanguageIsRefused() = runTest {
        val refused = listOf(
            PushRegistration("not-a-push-token", "expo", "ios", null),
            PushRegistration("ExponentPushToken[short]", "expo", "ios", null),
            PushRegistration("ExponentPushToken[abcdefgh]", "fcm", "ios", null),
            PushRegistration("ExponentPushToken[abcdefgh]", "expo", "web", null),
            PushRegistration("ExponentPushToken[abcdefgh]", "expo", "ios", "not a language"),
        )
        for (registration in refused) {
            assertFailsWith<InvalidPushDeviceException>(registration.toString()) { register(org, member, registration) }
        }
        assertTrue(repository.devices.isEmpty(), "Nothing registered")
    }

    @Test
    fun onlyTheMembersOwnPhoneIsRemovedAndOnlyOnce() = runTest {
        val device = register(org, member, PushRegistration("ExponentPushToken[abcdefgh]", "expo", "ios", null))

        assertFailsWith<PushDeviceNotFoundException> { remove(org, Uuid.random(), device.id) }
        assertFailsWith<PushDeviceNotFoundException> { remove(Uuid.random(), member, device.id) }
        remove(org, member, device.id)
        assertTrue(repository.devices.isEmpty())
        assertFailsWith<PushDeviceNotFoundException> { remove(org, member, device.id) }
    }
}
