// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import kotlinx.coroutines.test.runTest
import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.kernel.events.GateSummary
import run.ostrom.server.notifications.domain.ApprovalChannel
import run.ostrom.server.notifications.domain.ApprovalDelivery
import run.ostrom.server.notifications.domain.ApprovalNotices
import run.ostrom.server.notifications.domain.ApprovalRouter
import run.ostrom.server.notifications.domain.ApprovalSender
import run.ostrom.server.notifications.domain.Approver
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.PushMessage
import run.ostrom.server.notifications.domain.PushSender
import run.ostrom.server.notifications.domain.SwitchedOff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The other modules' ways to tell an approver (Slack's): queued while they reach them and the switch is on. */
class ApprovalChannelsTest {
    private val org = Uuid.random()
    private val bob = Approver(Uuid.random(), Uuid.random(), email = null, pushes = false)
    private val carol = Approver(Uuid.random(), Uuid.random(), email = null, pushes = false)
    private val dan = Approver(Uuid.random(), Uuid.random(), email = null, pushes = false)
    private val approvers = MapApprovers(mapOf("bob" to bob, "carol" to carol, "dan" to dan))
    private val notices = ApprovalNotices("https://console.ostrom.test")
    private val mailing = MailAvailability { false }
    private val now = Instant.parse("2026-11-15T08:00:00Z")

    /** Reaches Bob and Carol, records what it sends. */
    private inner class ChatChannel : ApprovalChannel {
        override val id: String = "chat"
        val sent = mutableListOf<Pair<ApprovalDelivery, String>>()

        override suspend fun reachable(orgId: Uuid, memberIds: Set<Uuid>): Set<Uuid> =
            memberIds.intersect(setOf(bob.memberId, carol.memberId))

        override suspend fun send(delivery: ApprovalDelivery, gate: GateSummary, link: String): ChannelResult {
            sent += delivery to link
            return ChannelResult.DELIVERED
        }
    }

    /** No approver here turned requests on: nothing is pushed. */
    private object NoPush : PushSender {
        override suspend fun send(messages: List<PushMessage>) = error("Nothing is pushed")
    }

    private fun sender(channel: ApprovalChannel?) = ApprovalSender(
        approvers, InMemoryPushDevicesRepository(), SetLiveCredentials(mutableSetOf()), notices,
        NoPush, RecordingMailSender(), mailing, { listOfNotNull(channel) },
    )

    @Test
    fun anApproverIsToldOnABoundChannelThatReachesThemWhileTheirWaitingSwitchIsOn() {
        runTest {
            val chat = ChatChannel()
            val gate = sampleGate(pending = listOf("bob", "carol", "dan"))
            val off = OffSettings(setOf(SwitchedOff(carol.memberId, "chat", NotificationKind.WAITING)))
            val router = ApprovalRouter(MapGates(listOf(gate)), approvers, mailing, sender(chat), { listOf(chat) }, off)
            val entries = listOf(
                approvalRequested(1, Uuid.random(), gate, "bob"),
                approvalRequested(2, Uuid.random(), gate, "carol"),
                approvalRequested(3, Uuid.random(), gate, "dan"),
            )

            val queued = router.queue(org, entries, now) { Uuid.random() }

            assertEquals(listOf(1L to "chat"), queued.map { it.eventSeq to it.channel }, "Carol off, Dan unreachable")
            assertEquals(listOf(setOf(bob.memberId, carol.memberId, dan.memberId)), off.asked)
            val delivery = queued.single()
            assertEquals(ChannelResult.DELIVERED, router.send(delivery, gate))
            assertEquals(listOf(delivery to notices.link(delivery.approvalId)), chat.sent)
            val elsewhere = delivery.copy(channel = "other")
            assertEquals(ChannelResult.failed("channel_unavailable"), router.send(elsewhere, gate), "Not bound")
        }
    }

    @Test
    fun withoutABoundChannelNothingMoreIsQueuedAndItsDeliveryIsUnavailable() {
        runTest {
            val gate = sampleGate(pending = listOf("bob"))
            val off = OffSettings()
            val router = ApprovalRouter(MapGates(listOf(gate)), approvers, mailing, sender(null), { emptyList() }, off)

            val queued = router.queue(org, listOf(approvalRequested(1, Uuid.random(), gate, "bob")), now) {
                Uuid.random()
            }

            assertEquals(emptyList(), queued)
            assertEquals(emptyList(), off.asked, "No channel, no switch read")
            val chat = approvalDelivery(org, gate, channel = "chat")
            assertEquals(ChannelResult.failed("channel_unavailable"), sender(null).send(chat, gate))
        }
    }

    @Test
    fun withoutSwitchesABoundChannelTellsWhomItReachesAndANonMemberNobody() {
        runTest {
            val chat = ChatChannel()
            val gate = sampleGate(pending = listOf("bob", "nobody"))
            val router = ApprovalRouter(MapGates(listOf(gate)), approvers, mailing, sender(chat), { listOf(chat) })
            val bobs = router.queue(org, listOf(approvalRequested(1, Uuid.random(), gate, "bob")), now) {
                Uuid.random()
            }
            assertEquals(listOf("chat"), bobs.map { it.channel })
            val nobody = router.queue(org, listOf(approvalRequested(2, Uuid.random(), gate, "nobody")), now) {
                Uuid.random()
            }
            assertEquals(emptyList(), nobody)
        }
    }
}
