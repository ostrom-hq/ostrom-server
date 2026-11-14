// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.qualifier.named
import org.koin.dsl.module
import run.ostrom.server.events.EventsModule
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.events.WaitSummaries
import run.ostrom.server.kernel.events.WaitSummary
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.notifications.domain.ApprovalDelivery
import run.ostrom.server.notifications.domain.DeliverNotificationsUseCase
import run.ostrom.server.notifications.domain.DeliveryOutcome
import run.ostrom.server.notifications.domain.DeliveryState
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationChannel
import run.ostrom.server.notifications.domain.NotificationDeliveriesRepository
import run.ostrom.server.notifications.domain.NotificationDelivery
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.NotificationSettingsService
import run.ostrom.server.notifications.infrastructure.NotificationDeliveriesRepositoryImpl
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The router's delivery queue on PostgreSQL as the serving role: queued with the journal's cursor, unique per event,
 * member and channel, claimed under a lease, recorded, given up and purged, confined to its organization, and the
 * worker the server starts telling a member of a wait end to end.
 */
class NotificationDeliveriesPostgresTest {
    private val modules = listOf(InstanceModule, IdentityModule, OrgsModule, EventsModule, NotificationsModule)
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val mia = Uuid.parse("0000000a-0000-0000-0000-0000000000b1")
    private val t0 = Instant.parse("2026-11-03T08:00:00Z")
    private val channel = FakeChannel("fake", setOf(mia))
    private val overrides = module {
        single<SecretCipher> { PlainCipher() }
        single<NotificationChannel>(named("fake")) { channel }
        single<WaitSummaries> {
            WaitSummaries { _, ids ->
                ids.map { WaitSummary(it, mia.toString(), "s-1", "ask_human", "human", true, "Ship?") }
            }
        }
    }

    private fun delivery(org: Uuid, seq: Long, at: Instant = t0, member: Uuid = mia, channel: String = "push") =
        NotificationDelivery(notification(org, "w-$seq", member).copy(eventSeq = seq, createdAt = at), channel, 0)

    private suspend fun TestServer.count(where: String): Long = database.superuser.fetchAll(
        "SELECT count(*) AS n FROM notifications_deliveries WHERE $where",
    ).getOrThrow().first().get("n").asLong()

    private suspend fun TestServer.row(id: Uuid): String = database.superuser.fetchAll(
        "SELECT state || ':' || attempts || ':' || coalesce(last_error, '-') || ':' || " +
            "(delivered_at IS NOT NULL) AS r FROM notifications_deliveries WHERE id = '$id'",
    ).getOrThrow().first().get("r").asString()

    private fun TestServer.repository(journal: OrgEventJournal = FakeJournal()) =
        NotificationDeliveriesRepositoryImpl(koin.get<JdbcDatabase>()) { journal }

    @Test
    fun aPageIsQueuedOnceWithTheCursorAndNothingWhenTheCursorMoved() = serverTest(modules, overrides) { server ->
        val journal = FakeJournal()
        val repository = server.repository(journal)
        val position = JournalPosition(orgA, 0, 3)
        val first = delivery(orgA, 1)
        val page = listOf(first, delivery(orgA, 2), delivery(orgA, 1, channel = "fax"))
        assertTrue(repository.enqueue(position, 3, page))
        assertTrue(repository.enqueue(position, 3, listOf(delivery(orgA, 1))), "The same event, member and channel")
        assertEquals(3, server.count("org_id = '$orgA'"))
        assertEquals(listOf(position to 3L, position to 3L), journal.advanced)

        journal.moves = false
        assertFalse(repository.enqueue(position, 3, listOf(delivery(orgA, 9))))
        assertEquals(0, server.count("event_seq = 9"), "Nothing without the cursor")
        assertEquals("pending:0:-:false", server.row(first.notification.id))
    }

    @Test
    fun dueDeliveriesAreClaimedUnderALeaseRecordedAndThenNoLongerDue() = serverTest(modules, overrides) { server ->
        val repository = server.repository()
        val position = JournalPosition(orgA, 0, 3)
        val (one, two, three) = (1L..3L).map { delivery(orgA, it, t0 + it.seconds) }
        repository.enqueue(position, 3, listOf(one, two, three))
        repository.enqueue(JournalPosition(orgB, 0, 1), 1, listOf(delivery(orgB, 1, t0 + 10.seconds)))
        val now = t0 + 1.minutes
        assertEquals(listOf(orgA, orgB), repository.workingOrgs(now, 10), "The longest waiting first")
        assertEquals(listOf(orgA), repository.workingOrgs(now, 1))
        assertEquals(emptyList(), repository.workingOrgs(t0, 10), "Nothing is due before it is made")

        val claimed = repository.claimDue(orgA, 2, now, 1.minutes)
        assertEquals(listOf(one, two), claimed, "Read back whole, the longest due first")
        assertEquals(listOf(three), repository.claimDue(orgA, 5, now, 1.minutes), "A claimed one is skipped")
        assertEquals(emptyList(), repository.claimDue(orgA, 5, now, 1.minutes))
        assertEquals(listOf(orgB), repository.workingOrgs(now, 10))

        repository.record(orgA, one.notification.id, DeliveryOutcome(DeliveryState.DELIVERED, 1, now, null), now)
        val error = "timeout-and-much-more-text-than-we-keep-1234"
        val retry = DeliveryOutcome(DeliveryState.PENDING, 1, now + 10.seconds, error)
        repository.record(orgA, two.notification.id, retry, now)
        assertEquals("delivered:1:-:true", server.row(one.notification.id))
        repository.record(orgA, one.notification.id, retry, now)
        assertEquals("delivered:1:-:true", server.row(one.notification.id), "A late replica never overwrites it")
        assertEquals("pending:1:timeout-and-much-more-text-than-we-keep-:false", server.row(two.notification.id))
        assertEquals(emptyList(), repository.claimDue(orgA, 5, now + 9.seconds, 1.minutes), "Not before its backoff")
        assertEquals(listOf(two.copy(attempts = 1)), repository.claimDue(orgA, 5, now + 10.seconds, 1.minutes))
        assertEquals(
            listOf(three, two.copy(attempts = 1)),
            repository.claimDue(orgA, 5, now + 2.minutes, 1.minutes),
            "Their leases ended",
        )
    }

    @Test
    fun aKindOfALaterVersionIsFailedRatherThanSent() = serverTest(modules, overrides) { server ->
        val repository = server.repository()
        val later = delivery(orgA, 1)
        repository.enqueue(JournalPosition(orgA, 0, 1), 1, listOf(later, delivery(orgA, 2)))
        server.database.superuser.execute(
            "UPDATE notifications_deliveries SET kind = 'later' WHERE id = '${later.notification.id}'",
        ).getOrThrow()
        val claimed = repository.claimDue(orgA, 5, t0 + 1.minutes, 1.minutes)
        assertEquals(listOf(2L), claimed.map { (it as NotificationDelivery).notification.eventSeq })
        assertEquals("failed:0:unknown_kind:false", server.row(later.notification.id))

        server.database.superuser.execute("UPDATE notifications_deliveries SET kind = 'later'").getOrThrow()
        assertEquals(emptyList(), repository.claimDue(orgA, 5, t0 + 1.hours, 1.minutes), "Only unknown kinds")
    }

    @Test
    fun anApprovalIsQueuedWithItsRequestAndClaimedBackWholeAndOneMissingWhatItNamesIsFailed() =
        serverTest(modules, overrides) { server ->
            val repository = server.repository()
            val gate = sampleGate()
            val approval = approvalDelivery(orgA, gate, memberId = mia).copy(eventSeq = 1, createdAt = t0)
            val email = approval.copy(id = Uuid.random(), channel = "email")
            val broken = (2L..4L).map {
                approvalDelivery(orgA, gate, memberId = mia).copy(eventSeq = it, createdAt = t0)
            }
            repository.enqueue(JournalPosition(orgA, 0, 4), 4, listOf(approval, email, delivery(orgA, 5)) + broken)
            assertEquals(1, server.count("event_seq = 1 AND channel = 'push' AND kind = 'approval'"))
            assertEquals(
                "approval:${approval.approvalId}:${gate.id}:bob:ana asks for your yes",
                server.database.superuser.fetchAll(
                    "SELECT collapse_key || ':' || gate_id || ':' || approver || ':' || title AS r " +
                        "FROM notifications_deliveries WHERE id = '${approval.id}'",
                ).getOrThrow().first().get("r").asString(),
            )
            assertEquals(1, server.count("approval_id IS NULL AND gate_id IS NULL AND approver IS NULL"), "The wait's")
            val (noRequest, noGate, noApprover) = broken
            suspend fun unset(row: ApprovalDelivery, column: String) = server.database.superuser.execute(
                "UPDATE notifications_deliveries SET $column = NULL WHERE id = '${row.id}'",
            ).getOrThrow()
            unset(noRequest, "approval_id")
            unset(noGate, "gate_id")
            unset(noApprover, "approver")

            val claimed = repository.claimDue(orgA, 10, t0 + 1.minutes, 1.minutes)

            assertEquals(setOf(approval, email), claimed.filterIsInstance<ApprovalDelivery>().toSet())
            assertEquals(listOf(5L), claimed.filterIsInstance<NotificationDelivery>().map { it.notification.eventSeq })
            assertEquals(3, server.count("state = 'failed' AND last_error = 'unknown_kind'"))

            // An approval is no kind of the members' switches: their settings are the same with one queued.
            val settings = server.koin.get<NotificationSettingsService>().settings(orgA, mia)
            assertEquals(listOf("fake", "push"), settings.channels)
            assertEquals(
                NotificationKind.entries.toSet(),
                settings.settings.map { it.kind }.toSet(),
                "No approval kind, no e-mail channel",
            )
        }

    @Test
    fun theUpkeepGivesUpLateOnesAndDeletesOldOnesAcrossOrganizations() = serverTest(modules, overrides) { server ->
        val repository = server.repository()
        val late = delivery(orgA, 1)
        val old = delivery(orgB, 1)
        val fresh = delivery(orgA, 2, t0 + 2.hours)
        repository.enqueue(JournalPosition(orgA, 0, 2), 2, listOf(late, fresh))
        repository.enqueue(JournalPosition(orgB, 0, 1), 1, listOf(old))
        repository.record(orgB, old.notification.id, DeliveryOutcome(DeliveryState.DELIVERED, 1, t0, null), t0)
        val now = t0 + 1.days + 1.hours

        assertEquals(2, repository.maintain(now - 22.hours, t0 + 1.hours, t0 + 1.minutes, 10))
        assertEquals("failed:0:expired:false", server.row(late.notification.id))
        assertEquals(0, server.count("id = '${old.notification.id}'"))
        assertEquals("pending:0:-:false", server.row(fresh.notification.id))
        assertEquals(2, repository.maintain(now, now - 1.hours, now - 1.days, 10), "Fresh given up, late deleted")
        assertEquals(0, repository.maintain(now, now - 1.hours, now - 1.days, 10))
    }

    @Test
    fun anOrganizationNeitherClaimsNorRecordsAnothersAndADeletedOneLosesItsQueue() =
        serverTest(modules, overrides) { server ->
            val repository = server.repository()
            val mine = delivery(orgA, 1)
            repository.enqueue(JournalPosition(orgA, 0, 1), 1, listOf(mine))
            repository.enqueue(JournalPosition(orgB, 0, 1), 1, listOf(delivery(orgB, 1)))
            val now = t0 + 1.minutes
            assertEquals(listOf(orgB), repository.claimDue(orgB, 5, now, 1.minutes).map { it.orgId })
            repository.record(orgB, mine.notification.id, DeliveryOutcome(DeliveryState.FAILED, 1, now, "x"), now)
            assertEquals("pending:0:-:false", server.row(mine.notification.id), "Not B's row")

            server.koin.get<OrgDeletionListener>(named("notifications-deliveries")).deleted(orgA)
            assertEquals(0, server.count("org_id = '$orgA'"))
            server.koin.get<OrgDeletionListener>(named("notifications-deliveries")).purge(orgB)
            assertEquals(0, server.count("TRUE"))
        }

    @Test
    fun withoutTheAutomationsModuleNoWaitIsKnownAndNothingIsQueued() =
        serverTest(modules, module { single<SecretCipher> { PlainCipher() } }) { server ->
            val publisher = server.koin.get<OrgEventPublisher>()
            val wait = OrgEventDraft(OrgEventType.WAIT_CREATED, "w-1", mia, JsonObject(emptyMap()))
            publisher.publish(orgA, listOf(wait))
            val deliver = server.koin.get<DeliverNotificationsUseCase>()
            assertEquals(0, deliver.fanOut())
            publisher.publish(orgA, listOf(wait))
            assertEquals(0, deliver.fanOut())
            assertEquals(emptyList(), server.koin.get<OrgEventJournal>().behind("notifications", 10), "It moved on")
        }

    @Test
    fun theWorkerTheServerStartsTellsTheMemberAWaitAsksOnce() = serverTest(modules, overrides) { server ->
        val publisher = server.koin.get<OrgEventPublisher>()
        val journal = server.koin.get<OrgEventJournal>()
        fun asked(id: String) =
            OrgEventDraft(OrgEventType.WAIT_CREATED, id, mia, JsonObject(mapOf("waitId" to JsonPrimitive(id))))
        publisher.publish(orgA, listOf(asked("w-0")))
        journal.behind("notifications", 10)
        publisher.publish(orgA, listOf(asked("w-1")))
        val scope = CoroutineScope(Job())
        NotificationsModule.start(scope, server.koin)
        try {
            val told: List<Notification> = withContext(Dispatchers.Default) {
                withTimeout(20.seconds) {
                    while (channel.batches.isEmpty()) delay(100)
                    channel.batches.flatten()
                }
            }
            assertEquals(listOf("w-1"), told.map { it.waitId }, "What came before the router is not sent")
            assertEquals(listOf("Ship?"), told.map { it.body })
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) { while (server.count("state = 'delivered'") == 0L) delay(100) }
            }
        } finally {
            scope.cancel()
        }
        assertEquals(1, server.count("TRUE"), "Push reaches no phone: only the fake channel's delivery")
        assertEquals(0, server.koin.get<DeliverNotificationsUseCase>().maintain())
        assertEquals(0, server.koin.get<NotificationDeliveriesRepository>().workingOrgs(t0 + 1.days, 10).size)
    }
}
