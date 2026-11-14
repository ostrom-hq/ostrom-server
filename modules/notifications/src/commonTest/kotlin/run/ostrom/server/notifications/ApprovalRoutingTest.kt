// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.notifications.domain.ApprovalDelivery
import run.ostrom.server.notifications.domain.ApprovalEvent
import run.ostrom.server.notifications.domain.ApprovalNotices
import run.ostrom.server.notifications.domain.ApprovalRouter
import run.ostrom.server.notifications.domain.ApprovalRules
import run.ostrom.server.notifications.domain.ApprovalSender
import run.ostrom.server.notifications.domain.Approver
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushMessage
import run.ostrom.server.notifications.domain.PushNotSentException
import run.ostrom.server.notifications.domain.PushRejectedException
import run.ostrom.server.notifications.domain.PushSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Who is told of a request for a yes and how, and what one attempt to tell them produces. */
class ApprovalRoutingTest {
    private val org = Uuid.random()
    private val otherOrg = Uuid.random()
    private val bob = Approver(Uuid.random(), Uuid.random(), "bob@example.com", pushes = true)
    private val carol = Approver(Uuid.random(), Uuid.random(), "carol@example.com", pushes = false)
    private val dan = Approver(Uuid.random(), Uuid.random(), email = null, pushes = true)
    private val approvers = MapApprovers(mapOf("bob" to bob, "carol" to carol, "dan" to dan))
    private val devices = InMemoryPushDevicesRepository()
    private val live = mutableSetOf<Uuid>()
    private val notices = ApprovalNotices("https://console.ostrom.test")
    private val mail = RecordingMailSender()
    private val now = Instant.parse("2026-11-03T08:00:00Z")
    private var mails = true
    private var mailReads = 0
    private val mailing = MailAvailability {
        mailReads++
        mails
    }

    /** A push service whose answer depends on the phone. */
    private class PhonePushSender(private val answer: (String) -> Exception?) : PushSender {
        val sent = mutableListOf<List<PushMessage>>()

        override suspend fun send(messages: List<PushMessage>) {
            sent += messages
            answer(messages.single().to)?.let { throw it }
        }
    }

    private fun sender(push: PushSender = PhonePushSender { null }) =
        ApprovalSender(approvers, devices, SetLiveCredentials(live), notices, push, mail, mailing)

    private fun phone(approver: Approver, token: String, credential: Uuid? = Uuid.random(), orgId: Uuid = org) =
        PushDevice(
            Uuid.random(), orgId, approver.memberId, token, "fcm", "ios", null, Instant.DISTANT_PAST,
            Instant.DISTANT_PAST, credential?.let { PushCredential(approver.userId, it, browser = false) },
        ).also { device ->
            devices.devices += device
            credential?.let(live::add)
        }

    @Test
    fun anEventNamesItsRequestOnlyWhenTheGatesWroteIt() {
        val gate = sampleGate()
        val approval = Uuid.random()
        val event = approvalRequested(4, approval, gate, "bob")
        assertEquals(ApprovalEvent(4, approval, Uuid.parse(gate.id), "bob"), ApprovalRules.event(event))
        assertNull(ApprovalRules.event(event.copy(type = OrgEventType.APPROVAL_DECIDED)), "Another type")
        fun with(key: String, value: JsonElement) = event.copy(data = JsonObject(event.data + (key to value)))
        assertNull(ApprovalRules.event(with("approvalId", JsonPrimitive("not-a-uuid"))))
        assertNull(ApprovalRules.event(with("gateId", JsonPrimitive("not-a-uuid"))))
        assertNull(ApprovalRules.event(with("approver", JsonPrimitive(1))), "A number is no login")
        assertNull(ApprovalRules.event(with("approver", JsonObject(emptyMap()))), "Nor an object")
        assertNull(ApprovalRules.event(event.copy(data = JsonObject(emptyMap()))))
        assertNull(ApprovalRules.event(event.copy(data = JsonObject(event.data - "gateId"))))
        assertNull(ApprovalRules.event(event.copy(data = JsonObject(event.data - "approver"))))
    }

    @Test
    fun anApproverIsAskedWhileTheGateIsOpenTheirRequestWaitsAndTheyDidNotAskWhileAnotherMayAnswer() {
        val gate = sampleGate(askedBy = listOf("Ana"), pending = listOf("ana", "BOB"))
        assertTrue(ApprovalRules.asks(gate, "bob"))
        assertFalse(ApprovalRules.asks(gate, "ana"), "The asker, while Bob may answer")
        assertFalse(ApprovalRules.asks(gate, "carol"), "Not asked, or answered")
        assertTrue(ApprovalRules.asks(gate.copy(pendingApprovers = listOf("ana")), "ANA"), "The only approver")
        assertFalse(ApprovalRules.asks(gate.copy(open = false), "bob"), "Decided")
    }

    @Test
    fun anApproverIsToldByPushUnlessTheyTurnedRequestsOffAndByEmailWhenTheInstanceSendsAndTheyProvedAnAddress() {
        assertEquals(listOf("push", "email"), ApprovalRules.channels(bob, mails = true))
        assertEquals(listOf("push"), ApprovalRules.channels(bob, mails = false))
        assertEquals(listOf("email"), ApprovalRules.channels(carol, mails = true))
        assertEquals(listOf("push"), ApprovalRules.channels(dan, mails = true))
        assertEquals(emptyList(), ApprovalRules.channels(carol, mails = false))
    }

    @Test
    fun aPageQueuesOneDeliveryPerApproverStillAskedAndWay() {
        runTest {
            val open = sampleGate(askedBy = listOf("ana"), pending = listOf("bob", "carol", "dan", "ana", "nobody"))
            val decided = sampleGate(open = false)
            val gates = MapGates(listOf(open, decided))
            val router = ApprovalRouter(gates, approvers, mailing, sender())
            val ids = (1..10).map { Uuid.fromLongs(0, it.toLong()) }.iterator()
            val first = Uuid.random()
            val entries = listOf(
                approvalRequested(1, first, open, "bob"),
                approvalRequested(2, Uuid.random(), open, "carol"),
                approvalRequested(3, Uuid.random(), open, "dan"),
                approvalRequested(4, Uuid.random(), open, "ana"),
                approvalRequested(5, Uuid.random(), open, "nobody"),
                approvalRequested(6, Uuid.random(), decided, "bob"),
                approvalRequested(7, Uuid.random(), sampleGate(), "bob"),
                waitCreated(8, "w-1"),
            )

            val queued = router.queue(org, entries, now) { ids.next() }

            assertEquals(
                listOf(1L to "push", 1L to "email", 2L to "email", 3L to "push"),
                queued.map { it.eventSeq to it.channel },
                "Ana asked, nobody is no member, a decided or unknown gate tells nobody",
            )
            assertEquals(
                ApprovalDelivery(
                    id = Uuid.fromLongs(0, 1),
                    orgId = org,
                    memberId = bob.memberId,
                    approver = "bob",
                    approvalId = first,
                    gateId = Uuid.parse(open.id),
                    channel = "push",
                    attempts = 0,
                    eventSeq = 1,
                    title = "ana asks for your yes",
                    body = "Push to main · acme/app",
                    createdAt = now,
                ),
                queued.first(),
            )
            assertEquals("approval:$first", queued.first().collapseKey)
            assertEquals(1, gates.asked.size, "The page's gates in one read")
            assertEquals(listOf(org to setOf("bob", "carol", "dan", "nobody")), approvers.asked)
            assertEquals(1, mailReads, "The mail read once for the page")
        }
    }

    @Test
    fun aPageWithoutRequestOrWithoutApproverStillAskedReadsNoFurther() {
        runTest {
            val gates = MapGates(listOf(sampleGate(open = false)))
            val router = ApprovalRouter(gates, approvers, mailing, sender())

            assertEquals(emptyList(), router.queue(org, listOf(waitCreated(1, "w-1")), now) { Uuid.random() })
            assertTrue(gates.asked.isEmpty(), "No request, no gate read")
            val decided = approvalRequested(2, Uuid.random(), gates.gates.single(), "bob")
            assertEquals(emptyList(), router.queue(org, listOf(decided), now) { Uuid.random() })
            assertTrue(approvers.asked.isEmpty() && mailReads == 0, "Nobody asked, nobody read")
            assertEquals(emptyMap(), router.stillAsked(org, emptyList()))
            assertEquals(1, gates.asked.size, "Nothing claimed, no gate read")
        }
    }

    @Test
    fun aClaimedDeliveryIsSentOnlyWhileItsGateStillAsksItsApprover() {
        runTest {
            val open = sampleGate(pending = listOf("bob"))
            val answered = sampleGate(pending = listOf("carol"))
            val gates = MapGates(listOf(open, answered, sampleGate(open = false).copy(id = Uuid.random().toString())))
            val router = ApprovalRouter(gates, approvers, mailing, sender())
            val asked = approvalDelivery(org, open)
            val notAnymore = approvalDelivery(org, answered)
            val gone = approvalDelivery(org, sampleGate())

            assertEquals(mapOf(asked.id to open), router.stillAsked(org, listOf(asked, notAnymore, gone)))
        }
    }

    @Test
    fun aPushGoesToEachLivePhoneOfTheGatesOrganizationApartAndIsDeliveredWhenOnePhoneGotIt() {
        runTest {
            val first = phone(bob, "fcm-token-bob-1")
            phone(bob, "fcm-token-bob-2")
            phone(bob, "fcm-token-bob-other-org", orgId = otherOrg)
            phone(bob, "fcm-token-bob-unbound", credential = null)
            val revoked = Uuid.random()
            phone(bob, "fcm-token-bob-revoked", credential = revoked)
            live -= revoked
            devices.devices += first.copy(id = Uuid.random(), pushToken = "ExponentPushToken[old]", provider = "expo")
            val push = PhonePushSender { if (it == "fcm-token-bob-2") PushNotSentException("fcm_503") else null }
            val gate = sampleGate()
            val delivery = approvalDelivery(org, gate, approver = "BOB")

            val router = ApprovalRouter(MapGates(), approvers, mailing, sender(push))

            assertEquals(ChannelResult.DELIVERED, router.send(delivery, gate))
            assertEquals(listOf(1, 1), push.sent.map { it.size }, "One push per phone, apart")
            assertEquals(setOf("fcm-token-bob-1", "fcm-token-bob-2"), push.sent.flatten().map { it.to }.toSet())
            assertTrue(notices.push(delivery.approvalId, first) in push.sent.flatten())
        }
    }

    @Test
    fun aPushNoPhoneGotIsRetriedWhileOneMayStillAndFailedOtherwise() {
        runTest {
            phone(bob, "fcm-token-bob-1")
            phone(bob, "fcm-token-bob-2")
            val gate = sampleGate()
            val delivery = approvalDelivery(org, gate)
            suspend fun outcome(answer: (String) -> Exception?) = sender(PhonePushSender(answer)).send(delivery, gate)

            assertEquals(
                ChannelResult.retry("fcm_503"),
                outcome { if (it.endsWith("1")) PushRejectedException("fcm_403") else PushNotSentException("fcm_503") },
            )
            assertEquals(ChannelResult.failed("fcm_403"), outcome { PushRejectedException("fcm_403") })
            assertEquals(ChannelResult.retry("push"), outcome { IllegalStateException("The provider's answer") })
        }
    }

    @Test
    fun nothingIsPushedToAnApproverWhoTurnedRequestsOffHasNoPhoneOrLeft() {
        runTest {
            phone(carol, "fcm-token-carol")
            val gate = sampleGate()
            val sender = sender()
            assertEquals(ChannelResult.failed("requests_off"), sender.send(approvalDelivery(org, gate, "carol"), gate))
            assertEquals(ChannelResult.failed("no_device"), sender.send(approvalDelivery(org, gate, "dan"), gate))
            assertEquals(ChannelResult.failed("not_a_member"), sender.send(approvalDelivery(org, gate, "eve"), gate))
            val fax = approvalDelivery(org, gate, channel = "fax")
            assertEquals(ChannelResult.failed("channel_unavailable"), sender.send(fax, gate))
        }
    }

    @Test
    fun anEmailGoesToTheProvenAddressWhileTheInstanceSendsAndAFailingProviderIsRetried() {
        runTest {
            val gate = sampleGate()
            val delivery = approvalDelivery(org, gate, channel = "email")
            val sender = sender()

            assertEquals(ChannelResult.DELIVERED, sender.send(delivery, gate))
            assertEquals(listOf(notices.email(gate, delivery.approvalId, "bob@example.com")), mail.calls)
            mail.failures = 1
            assertEquals(ChannelResult.retry("mail"), sender.send(delivery, gate))
            mails = false
            assertEquals(ChannelResult.failed("mail_off"), sender.send(delivery, gate))
            val dans = approvalDelivery(org, gate, approver = "dan", channel = "email")
            assertEquals(ChannelResult.failed("no_email"), sender.send(dans, gate))
            assertEquals(2, mail.calls.size)
        }
    }
}
