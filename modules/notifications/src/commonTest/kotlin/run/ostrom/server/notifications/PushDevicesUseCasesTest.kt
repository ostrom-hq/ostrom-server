// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.notifications.domain.DeletePushDeviceUseCaseImpl
import run.ostrom.server.notifications.domain.InvalidPushDeviceException
import run.ostrom.server.notifications.domain.MAX_PHONES
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.notifications.domain.PushDeviceNotFoundException
import run.ostrom.server.notifications.domain.PushRegistration
import run.ostrom.server.notifications.domain.RegisterPushDeviceUseCaseImpl
import run.ostrom.server.notifications.infrastructure.EciesPushEncryption
import run.ostrom.server.notifications.infrastructure.PushDevicesOrgDeletionListener
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
    private val register = RegisterPushDeviceUseCaseImpl(repository, EciesPushEncryption(), clock)
    private val remove = DeletePushDeviceUseCaseImpl(repository)
    private val org = Uuid.random()
    private val member = Uuid.random()

    @Test
    fun aPhoneIsRegisteredWithItsTrimmedTokenItsLanguageAndTheTimeAndItsMemberKeepsTheirLatestTen() = runTest {
        val key = phoneKey()
        val registration = PushRegistration("  $FCM_TOKEN  ", "fcm", "ios", " fr-FR ", " $key ")
        val credential = PushCredential(Uuid.random(), Uuid.random(), browser = false)
        val device = register(org, member, registration, credential)

        assertEquals(FCM_TOKEN to key, device.pushToken to device.publicKey)
        assertEquals(Triple("fcm", "ios", "fr-FR"), Triple(device.provider, device.platform, device.locale))
        assertEquals(org to member, device.orgId to device.memberId)
        assertEquals(clock.now to clock.now, device.createdAt to device.seenAt)
        assertEquals(credential, device.credential, "The phone is notified through the credential it registered with")
        assertEquals(listOf(device), repository.devices)
        assertEquals(10, MAX_PHONES)
        assertEquals(MAX_PHONES, repository.kept)
    }

    @Test
    fun aPhoneWithoutALanguageOrWithABlankOneIsRegisteredWithNone() = runTest {
        val none = register(org, member, phone("$FCM_TOKEN-1", platform = "android"), null)
        val blank = register(org, member, phone("$FCM_TOKEN-2", platform = "android", locale = "  "), null)

        assertNull(none.locale)
        assertNull(blank.locale)
        assertEquals("android", none.platform)
    }

    @Test
    fun anythingButTheFcmTokenAndKeyOfAnIosOrAndroidPhoneInALanguageIsRefused() = runTest {
        val key = phoneKey()
        val refused = listOf(
            // An older app's Expo token, with or without a key: refused, the app registers FCM.
            PushRegistration("ExponentPushToken[abcdefgh]", "expo", "ios", null),
            PushRegistration(FCM_TOKEN, "expo", "ios", null, key),
            PushRegistration(FCM_TOKEN, "apns", "ios", null, key),
            PushRegistration(FCM_TOKEN, "fcm", "ios", "not a language", key),
            PushRegistration(FCM_TOKEN, "fcm", "ios", null),
            PushRegistration(FCM_TOKEN, "fcm", "ios", null, "  "),
            PushRegistration(FCM_TOKEN, "fcm", "ios", null, "bm90IGEga2V5"),
            PushRegistration("short", "fcm", "ios", null, key),
            PushRegistration("$FCM_TOKEN with spaces", "fcm", "ios", null, key),
            PushRegistration(FCM_TOKEN, "fcm", "web", null, key),
        )
        for (registration in refused) {
            assertFailsWith<InvalidPushDeviceException>(registration.toString()) {
                register(org, member, registration, null)
            }
        }
        assertTrue(repository.devices.isEmpty(), "Nothing registered")
    }

    @Test
    fun onlyTheMembersOwnPhoneIsRemovedAndOnlyOnce() = runTest {
        val device = register(org, member, phone(FCM_TOKEN), null)

        assertFailsWith<PushDeviceNotFoundException> { remove(org, Uuid.random(), device.id) }
        assertFailsWith<PushDeviceNotFoundException> { remove(Uuid.random(), member, device.id) }
        remove(org, member, device.id)
        assertTrue(repository.devices.isEmpty())
        assertFailsWith<PushDeviceNotFoundException> { remove(org, member, device.id) }
    }

    @Test
    fun aDeletedOrganizationsPhonesAreRemovedAtOnceAndAgainAtThePurge() = runTest {
        val listener = PushDevicesOrgDeletionListener(repository)
        val other = register(Uuid.random(), member, phone("$FCM_TOKEN-other"), null)
        register(org, member, phone("$FCM_TOKEN-deleted-1"), null)

        listener.deleted(org)
        assertEquals(listOf(other), repository.devices)
        register(org, member, phone("$FCM_TOKEN-deleted-2"), null)
        listener.purge(org)
        assertEquals(listOf(other), repository.devices)
    }

    private suspend fun phone(token: String, platform: String = "ios", locale: String? = null) =
        PushRegistration(token, "fcm", platform, locale, phoneKey())

    private companion object {
        const val FCM_TOKEN = "dGVzdC1mY20tdG9rZW4:APA91bH-0123456789_abcdef.ghij"
    }
}
