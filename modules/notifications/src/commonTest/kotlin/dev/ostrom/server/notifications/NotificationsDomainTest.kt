// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.notifications.domain.ApprovalNotices
import dev.ostrom.server.notifications.domain.EmailGateChannel
import dev.ostrom.server.notifications.domain.NoPersonEmails
import dev.ostrom.server.notifications.domain.PushDevicesUseCaseImpl
import dev.ostrom.server.notifications.domain.PushGateChannel
import dev.ostrom.server.notifications.domain.PushRegistration
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Who hears of a request, on which phone and at which address, and who registers a phone. */
class NotificationsDomainTest {
    private val notices = ApprovalNotices("https://console.example")
    private val person = GateActor("rev-b", ORG, ActorKind.USER, personId = "rev-b")

    @Test
    fun aNoticeNamesWhoAsksAndWhatAndLinksToTheRequestOnly() {
        val request = requestOf("rev-b")
        val push = notices.push(gate(), request, TOKEN)
        assertEquals("lyes asks for your yes", push.title)
        assertEquals("Merge into main", push.body)
        assertEquals(mapOf("kind" to "approval", "approvalId" to request.id.toString()), push.data)
        val email = notices.email(gate(), request, "rev@acme.dev")
        assertEquals("lyes asks for your yes: Merge into main", email.subject)
        assertTrue(email.text.contains("https://console.example/console/approvals/${request.id}"))
        assertEquals("x".repeat(120) + "…", notices.body(gate("x".repeat(200))), "a long label is clipped")
    }

    @Test
    fun everyPhoneOfEveryPendingApproverIsToldAtOnce() = runTest {
        val devices = MemoryPushDevices().apply {
            devices += device("rev-b")
            devices += device("rev-b", "ExponentPushToken[second-phone]")
            devices += device("rev-c", "ExponentPushToken[closed-one]")
        }
        val push = RecordingPush()
        val channel = PushGateChannel(devices, push, notices)
        assertEquals("push", channel.name)
        channel.requested(gate(), listOf(requestOf("rev-b"), requestOf("rev-c", RequestStatus.CLOSED, 2)))
        assertEquals(listOf(TOKEN, "ExponentPushToken[second-phone]"), push.batches.single().map { it.to })
        channel.requested(gate(), listOf(requestOf("nobody")))
        channel.decided(gate(), emptyList())
        assertEquals(1, push.batches.size, "no phone, no call; nothing once decided")
    }

    @Test
    fun anApproverWithAKnownAddressIsEmailedWhenResendIsConfigured() = runTest {
        val email = RecordingEmail()
        val channel = EmailGateChannel(MapEmails(mapOf("rev-b" to "rev@acme.dev")), email, notices)
        assertEquals("email", channel.name)
        val requests =
            listOf(requestOf("rev-b"), requestOf("rev-c", n = 2), requestOf("rev-b", RequestStatus.CLOSED, 3))
        channel.requested(gate(), requests)
        channel.decided(gate(), requests)
        assertEquals(listOf("rev@acme.dev"), email.sent.map { it.to })
        val off = RecordingEmail(configured = false)
        EmailGateChannel(MapEmails(mapOf("rev-b" to "rev@acme.dev")), off, notices).requested(gate(), requests)
        assertTrue(off.sent.isEmpty(), "no key, no email")
        assertNull(NoPersonEmails.emailOf(ORG, "rev-b"))
    }

    @Test
    fun aPersonRegistersAnExpoPhoneAndRemovesOnlyTheirOwn() = runTest {
        val repository = MemoryPushDevices()
        val devices = PushDevicesUseCaseImpl(repository, SettableClock())
        val saved = devices.register(person, PushRegistration(" $TOKEN ", "expo", "ios", " fr-FR "))
        assertEquals(listOf(TOKEN, "rev-b", "fr-FR"), listOf(saved.pushToken, saved.personId, saved.locale))
        assertEquals(NOW, saved.createdAt)
        val again = devices.register(person, PushRegistration("ExpoPushToken[abcdefgh1234]", "expo", "android", ""))
        assertNull(again.locale)
        val other = GateActor("rev-c", ORG, ActorKind.USER, personId = "rev-c")
        assertEquals(ErrorKind.NOT_FOUND, assertFailsWith<OstromException> { devices.remove(other, saved.id) }.kind)
        devices.remove(person, saved.id)
        assertEquals(ErrorKind.NOT_FOUND, assertFailsWith<OstromException> { devices.remove(person, saved.id) }.kind)
    }

    @Test
    fun aMalformedRegistrationIsRefused() = runTest {
        val devices = PushDevicesUseCaseImpl(MemoryPushDevices(), SettableClock())
        for (bad in listOf(
            PushRegistration("not-a-token", "expo", "ios", null),
            PushRegistration(TOKEN, "fcm", "ios", null),
            PushRegistration(TOKEN, "expo", "web", null),
            PushRegistration(TOKEN, "expo", "ios", "<script>"),
        )) {
            val refused = assertFailsWith<OstromException> { devices.register(person, bad) }
            assertEquals(ErrorKind.INVALID_REQUEST, refused.kind)
        }
    }

    @Test
    fun onlyAPersonRegistersOrRemovesAPhone() = runTest {
        val devices = PushDevicesUseCaseImpl(MemoryPushDevices(), SettableClock())
        val registration = PushRegistration(TOKEN, "expo", "ios", null)
        for (actor in listOf(
            GateActor("agent", ORG, ActorKind.AGENT_SESSION, launchId = "l1", personId = "lyes"),
            GateActor("cli", ORG, ActorKind.DEVICE, personId = "lyes"),
            GateActor("svc", ORG, ActorKind.USER),
        )) {
            val registering = assertFailsWith<OstromException> { devices.register(actor, registration) }
            assertEquals(ErrorKind.FORBIDDEN, registering.kind)
            val removing = assertFailsWith<OstromException> { devices.remove(actor, Uuid.random()) }
            assertEquals(ErrorKind.FORBIDDEN, removing.kind)
        }
    }
}
