// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.notifications.domain.ApprovalNotices
import run.ostrom.server.notifications.domain.Approver
import run.ostrom.server.notifications.domain.ApproverNotificationsChannel
import run.ostrom.server.notifications.domain.NotificationOutbox
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.notifications.domain.PushDevice
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApproverNotificationsChannelTest {
    private val org = Uuid.random()
    private val otherOrg = Uuid.random()
    private val bob = Approver(Uuid.random(), Uuid.random(), "bob@example.com", pushes = true)
    private val carol = Approver(Uuid.random(), Uuid.random(), "carol@example.com", pushes = false)
    private val dan = Approver(Uuid.random(), Uuid.random(), email = null, pushes = true)
    private val ana = Approver(Uuid.random(), Uuid.random(), "ana@example.com", pushes = true)
    private val approvers = MapApprovers(mapOf("bob" to bob, "carol" to carol, "dan" to dan, "ana" to ana))
    private val devices = InMemoryPushDevicesRepository()
    private val live = mutableSetOf<Uuid>()
    private val push = RecordingPushSender()
    private val mail = RecordingMailSender()
    private val notices = ApprovalNotices("https://console.ostrom.test")

    private fun TestScope.channel(mailing: Boolean = true): ApproverNotificationsChannel {
        val outbox = NotificationOutbox(push, mail, RecordingLogger())
        backgroundScope.launch { outbox.run() }
        return ApproverNotificationsChannel(approvers, devices, SetLiveCredentials(live), notices, outbox, mailing)
    }

    private fun phone(orgId: Uuid, approver: Approver, token: String, credential: Uuid? = Uuid.random()) =
        PushDevice(
            Uuid.random(), orgId, approver.memberId, token, "expo", "ios", null, Instant.DISTANT_PAST,
            Instant.DISTANT_PAST, credential?.let { PushCredential(approver.userId, it, browser = false) },
        ).also { device ->
            devices.devices += device
            credential?.let(live::add)
        }

    @Test
    fun eachApproverHearsOnTheirLivePhonesOfTheGatesOrganizationAndByEmail() = runTest {
        val first = phone(org, bob, "ExponentPushToken[bob-phone-1]")
        val second = phone(org, bob, "ExponentPushToken[bob-phone-2]")
        phone(otherOrg, bob, "ExponentPushToken[bob-other-org]")
        val revoked = Uuid.random()
        phone(org, bob, "ExponentPushToken[bob-revoked]", credential = revoked)
        live -= revoked
        phone(org, bob, "ExponentPushToken[bob-unbound]", credential = null)
        val gate = sampleGate(org)
        val request = sampleRequest(gate, "BOB")

        channel().requested(gate, listOf(request))
        runCurrent()

        // One push per phone: a phone Expo refuses is tried again alone, never the approver's other phones.
        assertEquals(listOf(1, 1), push.calls.map { it.size })
        val sent = push.calls.flatten()
        val phones = setOf(first.pushToken, second.pushToken)
        assertEquals(phones, sent.map { it.to }.toSet(), "Live phones of the org only")
        assertTrue(notices.push(request, first) in sent)
        assertEquals(listOf(notices.email(gate, request, "bob@example.com")), mail.calls)
        assertEquals(listOf(org to "BOB"), approvers.asked)
    }

    @Test
    fun anApproverWhoTurnedRequestsOffIsStillToldByEmailAndOneWithoutAddressOnlyByPush() = runTest {
        phone(org, carol, "ExponentPushToken[carol-phone]")
        phone(org, dan, "ExponentPushToken[dan-phone-1]")
        val gate = sampleGate(org)

        channel().requested(gate, listOf(sampleRequest(gate, "carol"), sampleRequest(gate, "dan")))
        runCurrent()

        assertEquals(listOf(listOf("ExponentPushToken[dan-phone-1]")), push.calls.map { c -> c.map { it.to } })
        assertEquals(listOf("carol@example.com"), mail.calls.map { it.to })
    }

    @Test
    fun theAskerIsNotToldWhileAnotherApproverMayAnswerNorAnyoneOfAnAnsweredRequest() = runTest {
        phone(org, ana, "ExponentPushToken[ana-phone-1]")
        val gate = sampleGate(org, askedBy = listOf("Ana"))
        val requests = listOf(
            sampleRequest(gate, "ana"),
            sampleRequest(gate, "bob"),
            sampleRequest(gate, "carol", RequestStatus.CLOSED),
        )

        channel().requested(gate, requests)
        runCurrent()

        assertTrue(push.calls.isEmpty(), "Bob has no phone, Ana asked")
        assertEquals(listOf("bob@example.com"), mail.calls.map { it.to })
    }

    @Test
    fun theOnlyApproverIsToldEvenWhenTheyAsked() = runTest {
        phone(org, ana, "ExponentPushToken[ana-phone-1]")
        val gate = sampleGate(org, askedBy = listOf("ana"))

        channel().requested(gate, listOf(sampleRequest(gate, "ana")))
        runCurrent()

        assertEquals(1, push.calls.size)
        assertEquals(listOf("ana@example.com"), mail.calls.map { it.to })
    }

    @Test
    fun anUnknownApproverIsSkippedAndNoEmailLeavesAnInstanceThatSendsNone() = runTest {
        phone(org, bob, "ExponentPushToken[bob-phone-1]")
        val gate = sampleGate(org)

        channel(mailing = false).requested(gate, listOf(sampleRequest(gate, "nobody"), sampleRequest(gate, "bob")))
        runCurrent()

        assertEquals(1, push.calls.size)
        assertTrue(mail.calls.isEmpty())
    }

    @Test
    fun nothingIsSentOnceAGateIsDecided() = runTest {
        phone(org, bob, "ExponentPushToken[bob-phone-1]")
        val gate = sampleGate(org)
        val channel = channel()

        // Asked, then answered before the outbox got to it: nothing asks for a yes already given.
        channel.requested(gate, listOf(sampleRequest(gate, "bob")))
        channel.decided(gate, listOf(sampleRequest(gate, "bob", RequestStatus.APPROVED)))
        runCurrent()

        assertEquals("notifications", channel.name)
        assertTrue(push.calls.isEmpty() && mail.calls.isEmpty())
        assertTrue(approvers.asked.isEmpty(), "Not even read")
    }
}
