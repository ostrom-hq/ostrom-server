// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.JournalSlice
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.events.SessionNames
import run.ostrom.server.kernel.events.WaitSummaries
import run.ostrom.server.kernel.events.WaitSummary
import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.notifications.domain.ApprovalDelivery
import run.ostrom.server.notifications.domain.ApprovalNotices
import run.ostrom.server.notifications.domain.ApprovalRouter
import run.ostrom.server.notifications.domain.ApprovalSender
import run.ostrom.server.notifications.domain.Approver
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.DeliverNotificationsUseCaseImpl
import run.ostrom.server.notifications.domain.DeliveryOutcome
import run.ostrom.server.notifications.domain.DeliveryRules
import run.ostrom.server.notifications.domain.DeliveryState
import run.ostrom.server.notifications.domain.NotificationChannel
import run.ostrom.server.notifications.domain.NotificationDelivery
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.NotificationRules
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushDevicesRepository
import run.ostrom.server.notifications.domain.PushMessage
import run.ostrom.server.notifications.domain.PushSender
import run.ostrom.server.notifications.domain.QueuedDelivery
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
    private val logger = RecordingLogger()
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

    private val bob = Approver(Uuid.random(), mia, "bob@example.com", pushes = true)
    private val gates = MapGates()
    private val pushes = RecordingPushSender()
    private val mail = RecordingMailSender()
    private val devices = InMemoryPushDevicesRepository()

    /** The session names of the next use case. */
    private var names: SessionNames? = SessionNames { _, _ -> emptyMap() }

    /** The approvals' router of the next use case, the default one when null. */
    private var router: ApprovalRouter? = null

    private fun approvals(push: PushSender = pushes): ApprovalRouter {
        val approvers = MapApprovers(mapOf("bob" to bob))
        val notices = ApprovalNotices("https://console.ostrom.test")
        val mailing = MailAvailability { true }
        val live = SetLiveCredentials(setOf(CREDENTIAL))
        val sender = ApprovalSender(approvers, devices, live, notices, push, mail, mailing)
        return ApprovalRouter(gates, approvers, mailing, sender)
    }

    private fun useCase(
        journal: FakeJournal,
        waits: WaitSummaries,
        deliveries: MemoryDeliveries,
        channels: List<NotificationChannel>,
        settings: OffSettings = OffSettings(),
    ): DeliverNotificationsUseCaseImpl {
        val made = ids.iterator()
        return DeliverNotificationsUseCaseImpl(
            journal, waits, names, settings, deliveries, { channels }, router ?: approvals(), clock, logger,
        ) { made.next() }
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
            assertEquals(listOf(DeliveryRules.SOURCE_TYPES), journal.read.map { it.second }.distinct())
            assertEquals(setOf(OrgEventType.WAIT_CREATED, OrgEventType.APPROVAL_REQUESTED), DeliveryRules.SOURCE_TYPES)
            val (a, b) = deliveries.enqueued
            assertEquals(orgA, a.first.orgId)
            val told = a.second.filterIsInstance<NotificationDelivery>()
            assertEquals(a.second, told, "No approval on the page")
            assertEquals(
                listOf("push" to mia, "chat" to mia),
                told.map { it.channel to it.notification.memberId },
                "Leo switched push off; a wait a timer resolves and an unknown wait tell nobody",
            )
            val first = told.first()
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
    fun aNotificationIsTitledWithItsSessionsNameReadOnceForThePage() {
        runTest {
            val waits = CountingWaits(
                listOf(
                    summary("w-1", mia),
                    summary("w-2", leo).copy(sessionId = "s-2"),
                    summary("w-3", mia).copy(sessionId = null),
                    summary("w-4", mia, open = false).copy(sessionId = "s-4"),
                ),
            )
            val journal = FakeJournal(
                positions = listOf(JournalPosition(orgA, 0, 4)),
                slices = mapOf(
                    orgA to JournalSlice(
                        listOf("w-1", "w-2", "w-3", "w-4").mapIndexed { i, id -> waitCreated(i + 1L, id) },
                        4,
                    ),
                ),
            )
            val asked = mutableListOf<Pair<Uuid, Set<String>>>()
            names = SessionNames { orgId, ids ->
                asked += orgId to ids
                mapOf("s-1" to "Fix the login page", "s-9" to "Elsewhere").filterKeys { it in ids }
            }
            val deliveries = MemoryDeliveries()

            useCase(journal, waits, deliveries, listOf(FakeChannel("push", setOf(mia, leo)))).fanOut()

            val told = deliveries.enqueued.single().second.filterIsInstance<NotificationDelivery>()
            assertEquals(
                listOf("w-1" to "Fix the login page", "w-2" to "Ostrom", "w-3" to "Ostrom"),
                told.map { it.notification.waitId to it.notification.title },
                "A session without a name, and a wait in no session, keep the default title",
            )
            assertEquals(listOf(orgA to setOf("s-1", "s-2")), asked, "Only the sessions of the waits that tell someone")
        }
    }

    @Test
    fun aPageWhoseWaitsNameNoSessionReadsNoName() {
        runTest {
            val waits = CountingWaits(listOf(summary("w-1", mia).copy(sessionId = null)))
            val journal = FakeJournal(
                positions = listOf(JournalPosition(orgA, 0, 1)),
                slices = mapOf(orgA to JournalSlice(listOf(waitCreated(1, "w-1")), 1)),
            )
            names = SessionNames { _, _ -> error("Never asked: no session to name") }
            val deliveries = MemoryDeliveries()

            val queued = useCase(journal, waits, deliveries, listOf(FakeChannel("push", setOf(mia)))).fanOut()

            assertEquals(1, queued)
        }
    }

    @Test
    fun aFailedReadOfTheNamesKeepsTheDefaultTitleAndQueuesThePage() {
        runTest {
            val journal = FakeJournal(
                positions = listOf(JournalPosition(orgA, 0, 1)),
                slices = mapOf(orgA to JournalSlice(listOf(waitCreated(1, "w-1")), 1)),
            )
            names = SessionNames { _, _ -> error("The database is down") }
            val deliveries = MemoryDeliveries()

            val waits = CountingWaits(listOf(summary("w-1", mia)))
            useCase(journal, waits, deliveries, listOf(FakeChannel("push", setOf(mia)))).fanOut()

            val told = deliveries.enqueued.single().second.filterIsInstance<NotificationDelivery>()
            assertEquals(listOf(NotificationRules.DEFAULT_TITLE), told.map { it.notification.title })
            assertEquals(
                listOf("notification session names read failed" to mapOf("error" to "IllegalStateException")),
                logger.events.map { it.second to it.third },
            )
        }
    }

    @Test
    fun withoutTheRunnersModuleNoSessionHasAName() {
        runTest {
            val journal = FakeJournal(
                positions = listOf(JournalPosition(orgA, 0, 1)),
                slices = mapOf(orgA to JournalSlice(listOf(waitCreated(1, "w-1")), 1)),
            )
            names = null
            val deliveries = MemoryDeliveries()

            val waits = CountingWaits(listOf(summary("w-1", mia)))
            useCase(journal, waits, deliveries, listOf(FakeChannel("push", setOf(mia)))).fanOut()

            val told = deliveries.enqueued.single().second.filterIsInstance<NotificationDelivery>()
            assertEquals(listOf(NotificationRules.DEFAULT_TITLE), told.map { it.notification.title })
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
            assertEquals(DeliveryOutcome(DeliveryState.FAILED, 0, now, "wait_closed"), deliveries.recorded[closed.id()])
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
            assertEquals(2, deliveries.due.getValue(orgA).size, "A had more than its share: the room left")
            assertEquals(0, deliveries.due.getValue(orgB).size, "B is not held back by A's burst")
        }
    }

    @Test
    fun eachOrganizationIsGivenItsShareOfThePassBeforeAnyTakesMore() {
        runTest {
            val deliveries = MemoryDeliveries()
            fun queue(org: Uuid, count: Int) = MutableList<QueuedDelivery>(count) {
                NotificationDelivery(notification(org, null), "push", 0)
            }
            deliveries.due[orgA] = queue(orgA, 100)
            deliveries.due[orgB] = queue(orgB, 3)
            deliveries.due[orgC] = queue(orgC, 30)

            val push = FakeChannel("push")
            val attempted = useCase(FakeJournal(), CountingWaits(emptyList()), deliveries, listOf(push))()

            assertEquals(DeliveryRules.BATCH, attempted)
            assertEquals(
                listOf(orgA to 16, orgB to 16, orgC to 16, orgA to 15),
                deliveries.claims,
                "A third each, the longest waiting first, then the room B left goes to A",
            )
            assertEquals(listOf(69, 0, 14), listOf(orgA, orgB, orgC).map { deliveries.due.getValue(it).size })
        }
    }

    @Test
    fun moreOrganizationsThanTheBatchTakeOneEachTheLongestWaitingFirst() {
        runTest {
            val deliveries = MemoryDeliveries()
            val orgs = List(DeliveryRules.BATCH + 10) { Uuid.random() }
            for (org in orgs) {
                deliveries.due[org] = mutableListOf(NotificationDelivery(notification(org, null), "push", 0))
            }

            val push = FakeChannel("push")
            val attempted = useCase(FakeJournal(), CountingWaits(emptyList()), deliveries, listOf(push))()

            assertEquals(DeliveryRules.BATCH, attempted)
            assertEquals(orgs.take(DeliveryRules.BATCH).map { it to 1 }, deliveries.claims)
            assertEquals(orgs.drop(DeliveryRules.BATCH), orgs.filter { deliveries.due.getValue(it).isNotEmpty() })
        }
    }

    @Test
    fun deliveriesFailedUnsentStillCountAgainstThePassesBatch() {
        runTest {
            val deliveries = MemoryDeliveries()
            deliveries.due[orgA] = MutableList(DeliveryRules.BATCH + 10) {
                NotificationDelivery(notification(orgA, "w-2"), "push", 0)
            }
            deliveries.due[orgB] = mutableListOf(NotificationDelivery(notification(orgB, null), "push", 0))
            val waits = CountingWaits(listOf(summary("w-2", mia, open = false)))

            val attempted = useCase(FakeJournal(), waits, deliveries, listOf(FakeChannel("push")))()

            assertEquals(1, attempted, "A's deliveries are about a closed wait")
            assertEquals(DeliveryRules.BATCH, deliveries.recorded.size, "At most a batch claimed, sent or not")
            assertEquals(listOf(orgA to 25, orgB to 25, orgA to 24), deliveries.claims)
        }
    }

    @Test
    fun aShareIsAnEvenPartOfTheBatchAndAtLeastOne() {
        assertEquals(DeliveryRules.BATCH, DeliveryRules.fairShare(0))
        assertEquals(DeliveryRules.BATCH, DeliveryRules.fairShare(1))
        assertEquals(DeliveryRules.BATCH / 3, DeliveryRules.fairShare(3))
        assertEquals(1, DeliveryRules.fairShare(DeliveryRules.ORGS_PER_PASS))
    }

    @Test
    fun oneOrganizationsFailureHoldsNeitherTheOthersNorTheAttemptsBack() {
        runTest {
            val journal = FakeJournal(
                positions = listOf(JournalPosition(orgA, 0, 2), JournalPosition(orgB, 0, 1)),
                slices = mapOf(orgB to JournalSlice(listOf(waitCreated(1, "w-1")), 1)),
            )
            val deliveries = MemoryDeliveries().apply { failingClaims = setOf(orgC) }
            val sent = NotificationDelivery(notification(orgA, null), "push", 0)
            val lost = NotificationDelivery(notification(orgA, null), "push", 0)
            deliveries.due[orgC] = mutableListOf()
            deliveries.due[orgA] = mutableListOf(sent, lost)
            deliveries.failingRecords = setOf(lost.id())
            val waits = CountingWaits(listOf(summary("w-1", mia)))

            assertEquals(2, useCase(journal, waits, deliveries, listOf(FakeChannel("push", setOf(mia))))())

            assertEquals(listOf(orgB), deliveries.enqueued.map { it.first.orgId }, "A's page failed to read")
            assertEquals(DeliveryState.DELIVERED, deliveries.recorded[sent.id()]?.state, "C's claim failed")
            assertEquals(
                listOf(
                    "notification fan-out failed" to "NoSuchElementException",
                    "notification claim failed" to "IllegalStateException",
                    "notification record failed" to "IllegalStateException",
                ),
                logger.events.map { it.second to it.third["error"] },
            )

            journal.slices = emptyMap()
            assertEquals(0, useCase(journal, waits, deliveries, emptyList()).fanOut(), "No channel, no read")
            assertEquals(listOf(orgA, orgB), journal.read.map { it.first.orgId })
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

    @Test
    fun aPagesRequestsForAYesAreQueuedAfterItsNotificationsWithoutTheMembersSwitches() {
        runTest {
            val gate = sampleGate(pending = listOf("bob")).also { gates.gates = listOf(it) }
            val approval = Uuid.random()
            val journal = FakeJournal(
                positions = listOf(JournalPosition(orgA, 0, 2)),
                slices = mapOf(
                    orgA to JournalSlice(listOf(waitCreated(1, "w-1"), approvalRequested(2, approval, gate, "bob")), 2),
                ),
            )
            val deliveries = MemoryDeliveries()
            // Mia (Bob's membership) switched every wait off: an approval is no kind of hers to switch.
            val settings = OffSettings(setOf(SwitchedOff(mia, "push", NotificationKind.WAITING)))
            val waits = CountingWaits(listOf(summary("w-1", mia)))

            val channels = listOf(FakeChannel("push", setOf(mia)))
            assertEquals(2, useCase(journal, waits, deliveries, channels, settings).fanOut())

            val queued = deliveries.enqueued.single().second
            assertEquals(listOf("push", "email"), queued.map { it.channel })
            assertEquals(listOf(approval, approval), queued.map { (it as ApprovalDelivery).approvalId })
            assertEquals(ids[0], queued.first().id)
            assertEquals(listOf(setOf(mia)), settings.asked, "The switches are read for the waits only")
        }
    }

    @Test
    fun aClaimedApprovalIsSentWhileItsGateAsksAndFailedUnsentOnceDecided() {
        runTest {
            val open = sampleGate(pending = listOf("bob"))
            val decided = sampleGate(open = false)
            gates.gates = listOf(open, decided)
            devices.devices += PushDevice(
                Uuid.random(), orgA, mia, "fcm-token-bob", "fcm", "ios", null, clock.now, clock.now,
                PushCredential(bob.userId, CREDENTIAL, browser = false),
            )
            val sent = approvalDelivery(orgA, open, memberId = mia)
            val emailed = approvalDelivery(orgA, open, channel = "email", memberId = mia).copy(attempts = 2)
            val late = approvalDelivery(orgA, decided, memberId = mia).copy(attempts = 1)
            val wait = NotificationDelivery(notification(orgA, null), "push", 0)
            val deliveries = MemoryDeliveries()
            deliveries.due[orgA] = mutableListOf(sent, emailed, late, wait)

            val noWaits = CountingWaits(emptyList())
            val attempted = useCase(FakeJournal(), noWaits, deliveries, listOf(FakeChannel("push")))()

            assertEquals(3, attempted, "The decided gate's delivery is not attempted")
            val now = clock.now
            assertEquals(DeliveryOutcome(DeliveryState.DELIVERED, 1, now, null), deliveries.recorded[sent.id])
            assertEquals(DeliveryOutcome(DeliveryState.DELIVERED, 3, now, null), deliveries.recorded[emailed.id])
            assertEquals(DeliveryOutcome(DeliveryState.FAILED, 1, now, "gate_decided"), deliveries.recorded[late.id])
            assertEquals(DeliveryState.DELIVERED, deliveries.recorded[wait.id]?.state)
            assertEquals(listOf("fcm-token-bob"), pushes.calls.flatten().map { it.to })
            assertEquals(listOf("bob@example.com"), mail.calls.map { it.to })
        }
    }

    @Test
    fun aSlowOrFailingApprovalIsRetriedAndAFailedGateReadHoldsTheClaimUntilItsLease() {
        runTest {
            val open = sampleGate(pending = listOf("bob"))
            gates.gates = listOf(open)
            devices.devices += PushDevice(
                Uuid.random(), orgA, mia, "fcm-token-bob", "fcm", "ios", null, clock.now, clock.now,
                PushCredential(bob.userId, CREDENTIAL, browser = false),
            )
            val deliveries = MemoryDeliveries()
            val slow = approvalDelivery(orgA, open, memberId = mia)
            deliveries.due[orgA] = mutableListOf(slow)
            val hanging = object : PushSender {
                override suspend fun send(messages: List<PushMessage>) = awaitCancellation()
            }
            val noWaits = CountingWaits(emptyList())
            router = approvals(hanging)
            useCase(FakeJournal(), noWaits, deliveries, emptyList())()
            val retried = DeliveryOutcome(DeliveryState.PENDING, 1, clock.now + 10.seconds, "timeout")
            assertEquals(retried, deliveries.recorded[slow.id])

            val broken = approvalDelivery(orgA, open, memberId = mia)
            deliveries.due[orgA] = mutableListOf(broken)
            devices.devices.clear()
            val failing = object : PushDevicesRepository by devices {
                override suspend fun ofMember(orgId: Uuid, memberId: Uuid): List<PushDevice> =
                    error("The database is down")
            }
            val approvers = MapApprovers(mapOf("bob" to bob))
            val mailing = MailAvailability { true }
            val sender = ApprovalSender(
                approvers, failing, SetLiveCredentials(emptySet()), ApprovalNotices(""), pushes, mail, mailing,
            )
            router = ApprovalRouter(gates, approvers, mailing, sender)
            useCase(FakeJournal(), noWaits, deliveries, emptyList())()
            assertEquals("no_result", deliveries.recorded[broken.id]?.error)

            val held = approvalDelivery(orgA, open, memberId = mia)
            deliveries.due[orgA] = mutableListOf(held)
            router = ApprovalRouter({ _, _ -> error("The database is down") }, approvers, mailing, sender)
            assertEquals(0, useCase(FakeJournal(), noWaits, deliveries, emptyList())())
            assertEquals(null, deliveries.recorded[held.id], "Claimed, unrecorded: back after its lease")
            val failed = logger.events.last()
            assertEquals("notification claim failed" to "IllegalStateException", failed.second to failed.third["error"])
        }
    }

    private fun NotificationDelivery.id() = notification.id

    private companion object {
        val CREDENTIAL: Uuid = Uuid.parse("0000000a-0000-0000-0000-0000000000c1")
    }
}
