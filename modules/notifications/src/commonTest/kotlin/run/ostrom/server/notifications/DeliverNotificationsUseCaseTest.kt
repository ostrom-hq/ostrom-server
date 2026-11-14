// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.JournalSlice
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.events.WaitSummaries
import run.ostrom.server.kernel.events.WaitSummary
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.DeliverNotificationsUseCaseImpl
import run.ostrom.server.notifications.domain.DeliveryOutcome
import run.ostrom.server.notifications.domain.DeliveryRules
import run.ostrom.server.notifications.domain.DeliveryState
import run.ostrom.server.notifications.domain.NotificationChannel
import run.ostrom.server.notifications.domain.NotificationDelivery
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.NotificationRules
import run.ostrom.server.notifications.domain.SwitchedOff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The router's worker: the fan-out of the journal's pages, the attempts, their outcomes, and the upkeep. */
class DeliverNotificationsUseCaseTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val orgC = Uuid.parse("0000000c-0000-0000-0000-000000000000")
    private val mia = Uuid.parse("0000000a-0000-0000-0000-0000000000b1")
    private val leo = Uuid.parse("0000000a-0000-0000-0000-0000000000b2")
    private val clock = TestClock()
    private val ids = (1..10).map { Uuid.fromLongs(0, it.toLong()) }

    private fun summary(id: String, assignee: Uuid, open: Boolean = true) =
        WaitSummary(id, assignee.toString(), "s-1", "ask_human", "human", open, "Deploy?")

    private class CountingWaits(private val known: List<WaitSummary>) : WaitSummaries {
        val asked = mutableListOf<Set<String>>()

        override suspend fun of(orgId: Uuid, ids: Set<String>): List<WaitSummary> {
            asked += ids
            return known.filter { it.id in ids }
        }
    }

    private fun useCase(
        journal: FakeJournal,
        waits: WaitSummaries,
        deliveries: MemoryDeliveries,
        channels: List<NotificationChannel>,
        settings: OffSettings = OffSettings(),
    ): DeliverNotificationsUseCaseImpl {
        val made = ids.iterator()
        return DeliverNotificationsUseCaseImpl(journal, waits, settings, deliveries, { channels }, clock) {
            made.next()
        }
    }

    @Test
    fun aPageBecomesOneDeliveryPerEventMemberAndChannelReachedAndSwitchedOn() {
        runTest {
            val waits = CountingWaits(
                listOf(summary("w-1", mia), summary("w-2", leo), summary("w-3", mia).copy(resolver = "time")),
            )
            val journal = FakeJournal(
                positions = listOf(
                    JournalPosition(orgA, 0, 9),
                    JournalPosition(orgB, 4, 6),
                    JournalPosition(orgC, 1, 3),
                ),
                slices = mapOf(
                    orgA to JournalSlice(
                        listOf("w-1", "w-2", "w-3", "w-9").mapIndexed { i, id -> waitCreated(i + 1L, id) } +
                            waitCreated(5, "s-1").copy(type = OrgEventType.TODO_UPDATED),
                        7,
                    ),
                    orgB to JournalSlice(emptyList(), 6),
                    orgC to JournalSlice(listOf(waitCreated(2, "w-1")), 3),
                ),
            )
            val deliveries = MemoryDeliveries().apply { refused = setOf(orgC) }
            val push = FakeChannel("push", setOf(mia, leo))
            val chat = FakeChannel("chat", setOf(mia))
            val settings = OffSettings(setOf(SwitchedOff(leo, "push", NotificationKind.WAITING)))

            val queued = useCase(journal, waits, deliveries, listOf(push, chat), settings).fanOut()

            assertEquals(2, queued, "C's page was queued by another replica")
            assertEquals(listOf(NotificationRules.SOURCE_TYPES), journal.read.map { it.second }.distinct())
            val (a, b) = deliveries.enqueued
            assertEquals(orgA, a.first.orgId)
            assertEquals(
                listOf("push" to mia, "chat" to mia),
                a.second.map { it.channel to it.notification.memberId },
                "Leo switched push off; a wait a timer resolves and an unknown wait tell nobody",
            )
            val first = a.second.first()
            assertEquals(0, first.attempts)
            assertEquals(ids[0], first.notification.id)
            assertEquals(orgA, first.notification.orgId)
            assertEquals(clock.now, first.notification.createdAt)
            assertEquals("w-1", first.notification.waitId)
            assertEquals(1, first.notification.eventSeq)
            assertEquals(orgB to emptyList<NotificationDelivery>(), b.first.orgId to b.second, "B moves on")
            assertEquals(listOf(setOf("w-1", "w-2", "w-3", "w-9"), setOf("w-1")), waits.asked, "No read without a wait")
            assertEquals(listOf(setOf(mia, leo), setOf(mia)), settings.asked, "No read without a member")
        }
    }

    @Test
    fun aPassAttemptsTheDueDeliveriesPerChannelAndRecordsEachOutcome() {
        runTest {
            val deliveries = MemoryDeliveries()
            val delivered = NotificationDelivery(notification(orgA, "w-1"), "push", 0)
            val silent = NotificationDelivery(notification(orgA, "w-1"), "push", 1)
            val refused = NotificationDelivery(notification(orgA, "w-1"), "push", 0)
            val gone = NotificationDelivery(notification(orgA, "w-1"), "fax", 0)
            val slow = NotificationDelivery(notification(orgB, null), "slow", 0)
            val broken = NotificationDelivery(notification(orgB, null), "broken", 4)
            val closed = NotificationDelivery(notification(orgA, "w-2"), "push", 0)
            val test = NotificationDelivery(notification(orgA, null), "fax", 0)
            deliveries.due[orgA] = mutableListOf(delivered, silent, refused, gone, closed, test)
            deliveries.due[orgB] = mutableListOf(slow, broken)
            val push = FakeChannel("push") { batch ->
                mapOf(batch[0].id to ChannelResult.DELIVERED, batch[2].id to ChannelResult.failed("rejected"))
            }
            val slowChannel = FakeChannel("slow") { awaitCancellation() }
            val brokenChannel = FakeChannel("broken") { error("The provider's answer") }
            val waits = CountingWaits(listOf(summary("w-1", mia), summary("w-2", mia, open = false)))

            val attempted = useCase(FakeJournal(), waits, deliveries, listOf(push, slowChannel, brokenChannel))()

            assertEquals(7, attempted, "The delivery about a closed wait is not attempted")
            assertEquals(listOf(setOf("w-1", "w-2")), waits.asked, "B's deliveries name no wait")
            val now = clock.now
            assertEquals(DeliveryOutcome(DeliveryState.DELIVERED, 1, now, null), deliveries.recorded[delivered.id()])
            assertEquals(
                DeliveryOutcome(DeliveryState.PENDING, 2, now + 30.seconds, "no_result"),
                deliveries.recorded[silent.id()],
            )
            assertEquals(DeliveryOutcome(DeliveryState.FAILED, 1, now, "rejected"), deliveries.recorded[refused.id()])
            assertEquals(
                DeliveryOutcome(DeliveryState.FAILED, 1, now, "channel_unavailable"),
                deliveries.recorded[gone.id()],
            )
            assertEquals(DeliveryOutcome(DeliveryState.FAILED, 1, now, "wait_closed"), deliveries.recorded[closed.id()])
            assertEquals("channel_unavailable", deliveries.recorded[test.id()]?.error, "One naming no wait is sent")
            assertEquals(
                DeliveryOutcome(DeliveryState.PENDING, 1, now + 10.seconds, "timeout"),
                deliveries.recorded[slow.id()],
            )
            assertEquals(
                DeliveryOutcome(DeliveryState.FAILED, 5, now, "no_result"),
                deliveries.recorded[broken.id()],
                "The fifth attempt is the last",
            )
            assertEquals(listOf(listOf(delivered, silent, refused).map { it.notification }), push.batches)
        }
    }

    @Test
    fun aPassAttemptsAtMostOneBatch() {
        runTest {
            val deliveries = MemoryDeliveries()
            deliveries.due[orgA] = MutableList(DeliveryRules.BATCH + 1) {
                NotificationDelivery(notification(orgA, null), "push", 0)
            }
            deliveries.due[orgB] = mutableListOf(NotificationDelivery(notification(orgB, null), "push", 0))
            val push = FakeChannel("push")

            val attempted = useCase(FakeJournal(), CountingWaits(emptyList()), deliveries, listOf(push))()
            assertEquals(DeliveryRules.BATCH, attempted)
            assertEquals(1, deliveries.due.getValue(orgA).size)
            assertEquals(1, deliveries.due.getValue(orgB).size, "B waits for the next pass")
        }
    }

    @Test
    fun theUpkeepGivesUpAfterAnHourAndDeletesAfterADay() {
        runTest {
            val deliveries = MemoryDeliveries()
            assertEquals(3, useCase(FakeJournal(), CountingWaits(emptyList()), deliveries, emptyList()).maintain())
            val now = clock.now
            assertEquals(listOf(listOf(now, now - 1.hours, now - 1.days)), deliveries.maintained)
        }
    }

    @Test
    fun aRetryWaitsTenSecondsThirtySecondsTwoMinutesThenTenMinutes() {
        val now = clock.now
        val retry = ChannelResult.retry("retry")
        assertEquals(
            listOf(now + 10.seconds, now + 30.seconds, now + 2.minutes, now + 10.minutes),
            (0..3).map { DeliveryRules.outcome(it, retry, now).nextAttemptAt },
        )
        assertEquals(DeliveryOutcome(DeliveryState.FAILED, 5, now, "retry"), DeliveryRules.outcome(4, retry, now))
        assertEquals(listOf("pending", "delivered", "failed"), DeliveryState.entries.map { it.wire })
        assertEquals(OrgEventType.WAIT_CREATED, waitCreated(1, "w").type)
    }

    private fun NotificationDelivery.id() = notification.id
}
