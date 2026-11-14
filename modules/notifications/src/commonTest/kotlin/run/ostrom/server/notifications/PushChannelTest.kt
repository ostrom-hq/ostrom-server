// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/test/kotlin/me/nathanfallet/teamcode/domain/notifications/NotificationsTest.kt (PushChannel); see NOTICE.
package run.ostrom.server.notifications

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import run.ostrom.core.api.models.PushTestResult
import run.ostrom.core.api.models.PushTestResults
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.EncryptedPush
import run.ostrom.server.notifications.domain.FIREBASE_SERVICE_ACCOUNT_FIELD
import run.ostrom.server.notifications.domain.FcmSender
import run.ostrom.server.notifications.domain.NoPushPhoneException
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.PROVIDER_FIELD
import run.ostrom.server.notifications.domain.PUSH_CHANNEL
import run.ostrom.server.notifications.domain.PushChannel
import run.ostrom.server.notifications.domain.PushProviders
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushEncryption
import run.ostrom.server.notifications.domain.PushOffException
import run.ostrom.server.notifications.domain.PushResult
import run.ostrom.server.notifications.domain.PushTestRateLimitedException
import run.ostrom.server.notifications.domain.SendTestPushUseCaseImpl
import run.ostrom.server.notifications.presentation.toDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TeamCode's `PushChannel`: who it reaches, what each phone gets, and what a batch's answers make. */
class PushChannelTest {
    private val org = Uuid.random()
    private val mia = Uuid.random()
    private val leo = Uuid.random()
    private val live = Uuid.random()
    private val firebase = mapOf(PROVIDER_FIELD to "firebase", FIREBASE_SERVICE_ACCOUNT_FIELD to "{key}")

    private class PlainEncryption : PushEncryption {
        override suspend fun acceptsKey(publicKey: String): Boolean = true

        override suspend fun encrypt(publicKey: String, plaintext: String): String =
            if (publicKey == "stale") error("does not open") else plaintext

        override suspend fun collapseKey(key: String): String = "ck:$key"
    }

    private class Settings(var values: Map<String, String>) : InstanceSettings {
        override suspend fun read(section: String): SettingsValues = SettingsValues(values)
    }

    private class Fcm(var answer: suspend (EncryptedPush) -> PushResult = { PushResult.SENT }) : FcmSender {
        val sent = mutableListOf<EncryptedPush>()

        override suspend fun send(push: EncryptedPush): PushResult {
            sent += push
            return answer(push)
        }
    }

    private val devices = InMemoryPushDevicesRepository()
    private val settings = Settings(firebase)
    private val fcm = Fcm()
    private val clock = TestClock()

    private fun channel(origin: String = "https://ostrom.example") = PushChannel(
        PushProviders(settings, { fcm }, { fcm }), PlainEncryption(), devices, SetLiveCredentials(setOf(live)), origin,
        clock,
    )

    private fun phone(
        member: Uuid,
        token: String,
        key: String? = "key",
        provider: String = "fcm",
        credential: Uuid? = live,
    ): PushDevice = testPhone(token, provider, "android", key).copy(
        orgId = org,
        memberId = member,
        credential = credential?.let { PushCredential(Uuid.random(), it, false) },
    ).also { devices.devices += it }

    private fun notification(member: Uuid = mia) = Notification(
        Uuid.random(), org, member, NotificationKind.WAITING, "Ostrom", "Deploy?", "s-1", "w-1", "wait:w-1",
        Instant.parse("2026-11-14T10:00:00Z"), 7,
    )

    @Test
    fun itReachesTheMembersWithALiveEncryptedPhoneWhileAProviderIsChosen() = runTest {
        phone(mia, "mia-phone")
        phone(leo, "leo-expo", provider = "expo")
        phone(leo, "leo-keyless", key = null)
        phone(leo, "leo-unsigned", credential = null)
        phone(leo, "leo-revoked", credential = Uuid.random())
        assertEquals(PUSH_CHANNEL, channel().id)
        assertEquals(setOf(mia), channel().reachable(org, setOf(mia, leo)))
        assertEquals(emptySet(), channel(origin = "").reachable(org, setOf(mia)), "No account of the app named")
        settings.values = mapOf(PROVIDER_FIELD to "firebase")
        assertEquals(emptySet(), channel().reachable(org, setOf(mia)), "No service account")
        settings.values = mapOf(PROVIDER_FIELD to "relay")
        assertEquals(setOf(mia), channel().reachable(org, setOf(mia)), "The relay of the published apps")
        settings.values = mapOf(PROVIDER_FIELD to "off")
        assertEquals(emptySet(), channel().reachable(org, setOf(mia)))
        val off = notification()
        assertEquals(mapOf(off.id to ChannelResult.failed("push_off")), channel().deliver(listOf(off)))
    }

    @Test
    fun eachPhoneGetsTheNotificationEncryptedAndGroupedByItsCollapseKey() = runTest {
        phone(mia, "mia-phone")
        val one = notification()
        assertEquals(mapOf(one.id to ChannelResult.DELIVERED), channel().deliver(listOf(one)))
        val push = fcm.sent.single()
        assertEquals("ck:$org:wait:w-1", push.collapseKey)
        assertEquals(3_600, push.ttlSeconds)
        val payload = Json.parseToJsonElement(push.payload).jsonObject
        assertEquals("waiting", payload.getValue("kind").jsonPrimitive.content)
        assertEquals("w-1", payload.getValue("waitId").jsonPrimitive.content)
        assertEquals("https://ostrom.example", payload.getValue("origin").jsonPrimitive.content)
        assertEquals(null, payload["approvalId"], "Absent ids are left out")
        val nobody = notification(leo)
        assertEquals(ChannelResult.failed("no_device"), channel().deliver(listOf(nobody)).getValue(nobody.id))
    }

    @Test
    fun theAnswersOfAMembersPhonesMakeOneOutcome() = runTest {
        val first = phone(mia, "mia-first")
        phone(mia, "mia-second")
        suspend fun outcome(answer: suspend (EncryptedPush) -> PushResult): ChannelResult {
            fcm.answer = answer
            val one = notification()
            return channel().deliver(listOf(one)).getValue(one.id)
        }
        val sentToFirst: suspend (EncryptedPush) -> PushResult =
            { if (it.token == first.pushToken) PushResult.SENT else PushResult.retry("fcm_503") }
        assertEquals(ChannelResult.DELIVERED, outcome(sentToFirst), "One phone got it")
        assertEquals(ChannelResult.retry("fcm_503"), outcome { PushResult.retry("fcm_503") })
        assertEquals(ChannelResult.retry("retry"), outcome { PushResult(PushResult.Status.RETRY) })
        assertEquals(ChannelResult.failed("fcm_400"), outcome { PushResult.rejected("fcm_400") })
        assertEquals(ChannelResult.failed("rejected"), outcome { PushResult(PushResult.Status.REJECTED) })
        assertEquals(ChannelResult.retry("timeout"), outcome { delay(11.seconds); PushResult.SENT })
        assertEquals(ChannelResult.failed("unregistered"), outcome { PushResult.UNREGISTERED })
        assertEquals(2, devices.forgotten.size, "A token the provider no longer knows is forgotten")
        assertEquals(ChannelResult.failed("no_device"), outcome { PushResult.SENT })
    }

    @Test
    fun aPhoneWhoseKeyNoLongerOpensFailsAlone() = runTest {
        phone(mia, "mia-stale", key = "stale")
        val one = notification()
        assertEquals(ChannelResult.failed("push_key_invalid"), channel().deliver(listOf(one)).getValue(one.id))
        assertEquals(emptyList(), fcm.sent)
    }

    @Test
    fun aTestGoesToTheCallersPhonesOnceEveryTenSeconds() = runTest {
        val test = SendTestPushUseCaseImpl({ channel() }, clock)
        assertFailsWith<NoPushPhoneException> { test(org, mia) }
        phone(mia, "mia-phone")
        assertFailsWith<PushTestRateLimitedException> { test(org, mia) }
        clock.now += 10.seconds
        fcm.answer = { PushResult.UNREGISTERED }
        assertEquals(listOf(PushResult.UNREGISTERED), test(org, mia))
        val sent = Json.parseToJsonElement(fcm.sent.single().payload).jsonObject
        assertEquals("test", sent.getValue("kind").jsonPrimitive.content)
        assertEquals(1, devices.forgotten.size)
        settings.values = emptyMap()
        assertFailsWith<PushOffException> { test(org, leo) }
        assertEquals(
            PushTestResults(true, listOf(PushTestResult("sent"), PushTestResult("retry", "fcm_503"))),
            listOf(PushResult.SENT, PushResult.retry("fcm_503")).toDto(),
        )
        assertEquals(PushTestResults(false, emptyList()), emptyList<PushResult>().toDto())
    }
}
