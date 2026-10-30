// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage

import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.usage.domain.ClientUsageEvent
import dev.ostrom.server.usage.domain.IngestUsageEventsUseCaseImpl
import dev.ostrom.server.usage.domain.InvalidUsageEventException
import dev.ostrom.server.usage.domain.InvalidUsageQueryException
import dev.ostrom.server.usage.domain.ListUsageEventsUseCaseImpl
import dev.ostrom.server.usage.domain.PurgeUsageUseCase
import dev.ostrom.server.usage.domain.PurgeUsageUseCaseImpl
import dev.ostrom.server.usage.domain.SpendReaderImpl
import dev.ostrom.server.usage.domain.TooManyUsageEventsException
import dev.ostrom.server.usage.domain.UsageCursor
import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageGroupBy
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRateLimitedException
import dev.ostrom.server.usage.domain.UsageRecorderImpl
import dev.ostrom.server.usage.domain.UsageRepository
import dev.ostrom.server.usage.domain.UsageSummaryUseCaseImpl
import dev.ostrom.server.usage.domain.WatchUsageUseCaseImpl
import dev.ostrom.server.usage.infrastructure.UsageFeedImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class UsageUseCasesTest {
    private val clock = TestClock()
    private val repository = InMemoryUsageRepository()
    private val feed = UsageFeedImpl()
    private val ingest = IngestUsageEventsUseCaseImpl(repository, feed, clock)

    @Test
    fun aClientEventIsRecordedForTheCallersOrgAndMemberWithoutCost() = runTest {
        val accepted = ingest(
            TestCallers.memberA,
            listOf(
                ClientUsageEvent(
                    UsageKind.SKILL,
                    "deploy",
                    sessionId = "s-1",
                    durationMillis = 1200,
                    outcome = "error",
                ),
            ),
        )

        assertEquals(1, accepted)
        val event = repository.events.single()
        assertEquals(TestCallers.orgA, event.orgId)
        assertEquals(TestCallers.memberA.memberId, event.memberId)
        assertEquals(UsageKind.SKILL, event.kind)
        assertEquals("s-1", event.sessionId)
        assertEquals(1200L, event.durationMillis)
        assertEquals("error", event.outcome)
        assertEquals(0L, event.costMicros, "Clients never declare a cost")
        assertEquals(TestCallers.epoch, event.at, "No time: now")
    }

    @Test
    fun aClientTimeIsKeptWhenItIsNotInTheFuture() = runTest {
        val past = TestCallers.epoch - 3.days
        val skewed = TestCallers.epoch + 4.minutes
        ingest(
            TestCallers.memberA,
            listOf(
                ClientUsageEvent(UsageKind.HOOK, "PreToolUse", at = past),
                ClientUsageEvent(UsageKind.SESSION, "s-2", at = skewed),
            ),
        )

        assertEquals(listOf(past, skewed), repository.events.map { it.at })
    }

    @Test
    fun aMemberRecordsAtMostSixThousandEventsAMinute() = runTest {
        val batch = List(500) { ClientUsageEvent(UsageKind.HOOK, "Stop") }
        repeat(12) { ingest(TestCallers.memberA, batch) }
        assertFailsWith<UsageRateLimitedException> { ingest(TestCallers.memberA, batch.take(1)) }
        assertEquals(1, ingest(TestCallers.adminA, batch.take(1)), "Another member has their own minute")

        clock.advance(1.minutes)
        assertEquals(500, ingest(TestCallers.memberA, batch), "A new minute starts")
        assertEquals(6_001, repository.events.size, "The refused batch recorded nothing")
    }

    @Test
    fun theCountersStayBoundedForgettingFinishedMinutesFirst() = runTest {
        val bounded = IngestUsageEventsUseCaseImpl(repository, feed, clock, maxMembers = 2)
        val one = listOf(ClientUsageEvent(UsageKind.HOOK, "Stop"))
        fun member() = TestCallers.memberA.copy(memberId = Uuid.random())
        repeat(3) { bounded(member(), one) }
        clock.advance(1.minutes)
        repeat(2) { bounded(member(), one) }
        assertEquals(5, repository.events.size)
    }

    @Test
    fun anEmptyBatchRecordsNothing() = runTest {
        assertEquals(0, ingest(TestCallers.memberA, emptyList()))
        assertEquals(emptyList(), repository.events)
    }

    @Test
    fun moreThanFiveHundredEventsAreRefused() = runTest {
        assertFailsWith<TooManyUsageEventsException> {
            ingest(TestCallers.memberA, List(501) { ClientUsageEvent(UsageKind.HOOK, "Stop") })
        }
        assertEquals(500, ingest(TestCallers.memberA, List(500) { ClientUsageEvent(UsageKind.HOOK, "Stop") }))
    }

    @Test
    fun malformedEventsAreRefusedAndNoneOfTheBatchIsRecorded() = runTest {
        val invalid = listOf(
            ClientUsageEvent(UsageKind.MODEL, "claude-opus-4-1"),
            ClientUsageEvent(UsageKind.SKILL, " "),
            ClientUsageEvent(UsageKind.SKILL, "x".repeat(201)),
            ClientUsageEvent(UsageKind.SKILL, "deploy", sessionId = ""),
            ClientUsageEvent(UsageKind.SKILL, "deploy", sessionId = "s".repeat(201)),
            ClientUsageEvent(UsageKind.SKILL, "deploy", durationMillis = -1),
            ClientUsageEvent(UsageKind.SKILL, "deploy", durationMillis = 7L * 24 * 3600 * 1000 + 1),
            ClientUsageEvent(UsageKind.SKILL, "deploy", outcome = ""),
            ClientUsageEvent(UsageKind.SKILL, "deploy", outcome = "o".repeat(65)),
            ClientUsageEvent(UsageKind.SKILL, "deploy", at = TestCallers.epoch + 6.minutes),
            ClientUsageEvent(UsageKind.SKILL, "deploy", at = TestCallers.epoch - 7.days - 1.seconds),
        )
        for (event in invalid) {
            assertFailsWith<InvalidUsageEventException>("Refused: $event") {
                ingest(TestCallers.memberA, listOf(ClientUsageEvent(UsageKind.MCP, "grafana/query"), event))
            }
        }
        assertEquals(emptyList(), repository.events)
        val longest = ClientUsageEvent(
            UsageKind.MCP,
            "x".repeat(200),
            "s".repeat(200),
            7L * 24 * 3600 * 1000,
            "o".repeat(64),
        )
        assertEquals(1, ingest(TestCallers.memberA, listOf(longest)), "The bounds are inclusive")
    }

    @Test
    fun ingestedEventsArePublishedLive() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { feed.subscribe(TestCallers.orgA).first() }
        ingest(TestCallers.memberA, listOf(ClientUsageEvent(UsageKind.SESSION, "s-3")))

        assertEquals("s-3", received.await().name)
    }

    @Test
    fun theRecorderWritesAndPublishes() = runTest {
        val recorder = UsageRecorderImpl(repository, feed, RecordingLogger())
        val received = async(start = CoroutineStart.UNDISPATCHED) { feed.subscribe(TestCallers.orgA).first() }
        val event = modelEvent(costMicros = 1500)

        recorder.record(event)

        assertEquals(listOf(event), repository.events)
        assertEquals(event, received.await())
    }

    @Test
    fun aFailedWriteIsLoggedNotThrownAndStillPublished() = runTest {
        val logger = RecordingLogger()
        repository.failure = IllegalStateException("database down")
        val recorder = UsageRecorderImpl(repository, feed, logger)
        val received = async(start = CoroutineStart.UNDISPATCHED) { feed.subscribe(TestCallers.orgA).first() }

        recorder.record(modelEvent(costMicros = 10))

        assertEquals(listOf(LogLevel.WARN to "usage event not recorded"), logger.events)
        assertEquals(10L, received.await().costMicros)
    }

    @Test
    fun recordWithCancellationPropagatesException() = runTest {
        repository.failure = CancellationException("caller gone")
        val recorder = UsageRecorderImpl(repository, feed, RecordingLogger())

        assertFailsWith<CancellationException> { recorder.record(modelEvent(costMicros = 10)) }
    }

    @Test
    fun theSpendReaderSumsTheOrgTheMemberAndTheAccount() = runTest {
        val account = Uuid.random()
        repository.events += listOf(
            modelEvent(100, memberId = TestCallers.adminA.memberId),
            modelEvent(200, memberId = TestCallers.memberA.memberId).copy(accountId = account),
            modelEvent(400, memberId = TestCallers.memberA.memberId, at = TestCallers.epoch - 40.days),
            modelEvent(800, orgId = TestCallers.orgB, memberId = TestCallers.adminB.memberId).copy(accountId = account),
        )
        val spend = SpendReaderImpl(repository)
        val since = TestCallers.epoch - 30.days

        assertEquals(300L, spend.spentMicros(TestCallers.orgA, null, since))
        assertEquals(200L, spend.spentMicros(TestCallers.orgA, TestCallers.memberA.memberId, since))
        assertEquals(200L, spend.accountSpentMicros(TestCallers.orgA, account, since))
        assertEquals(800L, spend.accountSpentMicros(TestCallers.orgB, account, since), "An account is read in its org")
    }

    @Test
    fun anAdminSummarizesTheOrgAndAMemberOnlyThemselves() = runTest {
        repository.events += listOf(
            modelEvent(100, memberId = TestCallers.adminA.memberId),
            modelEvent(250, memberId = TestCallers.memberA.memberId),
            modelEvent(999, memberId = TestCallers.memberA.memberId, at = TestCallers.epoch - 8.days),
        )
        val summary = UsageSummaryUseCaseImpl(repository, clock)

        val org = summary(TestCallers.adminA, 7, UsageGroupBy.MEMBER)
        assertEquals(TestCallers.epoch - 7.days, org.since)
        assertEquals(
            listOf(TestCallers.memberA.memberId.toString(), TestCallers.adminA.memberId.toString()),
            org.groups.map { it.key },
        )
        assertEquals(350L, org.total.costMicros)
        assertEquals(2L, org.total.events)
        assertEquals(200L, org.total.inputTokens)
        assertEquals(20L, org.total.outputTokens)
        assertEquals(10L, org.total.cacheReadTokens)
        assertEquals(2L, org.total.cacheWriteTokens)
        assertEquals(null, org.total.key)

        val mine = summary(TestCallers.memberA, 90, UsageGroupBy.MEMBER)
        assertEquals(listOf(TestCallers.memberA.memberId.toString()), mine.groups.map { it.key })
        assertEquals(1249L, mine.total.costMicros)
    }

    @Test
    fun aSummaryCoversOneToNinetyDays() = runTest {
        val summary = UsageSummaryUseCaseImpl(repository, clock)
        assertFailsWith<InvalidUsageQueryException> { summary(TestCallers.adminA, 0, UsageGroupBy.KIND) }
        assertFailsWith<InvalidUsageQueryException> { summary(TestCallers.adminA, 91, UsageGroupBy.KIND) }
        assertEquals(emptyList(), summary(TestCallers.adminA, 1, UsageGroupBy.KIND).groups)
    }

    @Test
    fun aMemberListsOnlyTheirOwnEventsAndTheLimitIsBounded() = runTest {
        repository.events += List(3) {
            modelEvent(
                1,
                memberId = TestCallers.adminA.memberId,
                at = TestCallers.epoch - it.minutes,
            )
        }
        repository.events += modelEvent(2, memberId = TestCallers.memberA.memberId)
        val list = ListUsageEventsUseCaseImpl(repository)

        assertEquals(4, list(TestCallers.adminA, null, 500).size)
        assertEquals(1, list(TestCallers.adminA, null, 0).size, "At least one")
        assertEquals(listOf(2L), list(TestCallers.memberA, null, 50).map { it.costMicros })
        assertEquals(2, list(TestCallers.adminA, UsageCursor(TestCallers.epoch, null), 50).size)
    }

    @Test
    fun anAdminWatchesTheWholeOrgAndAMemberOnlyThemselves() = runTest {
        val watch = WatchUsageUseCaseImpl(feed)
        var subscribed = 0
        val org = async(start = CoroutineStart.UNDISPATCHED) {
            watch(TestCallers.adminA) { subscribed++ }.take(2).toList()
        }
        val mine = async(start = CoroutineStart.UNDISPATCHED) { watch(TestCallers.memberA) { subscribed++ }.first() }
        val other = async(start = CoroutineStart.UNDISPATCHED) { watch(TestCallers.adminB).first() }
        runCurrent()
        assertEquals(2, subscribed, "Both subscriptions are active before the first event")

        feed.publish(modelEvent(5, memberId = TestCallers.adminA.memberId))
        feed.publish(modelEvent(7, memberId = TestCallers.memberA.memberId))
        feed.publish(modelEvent(9, orgId = TestCallers.orgB, memberId = TestCallers.adminB.memberId))

        assertEquals(listOf(5L, 7L), org.await().map { it.costMicros })
        assertEquals(7L, mine.await().costMicros)
        assertEquals(9L, other.await().costMicros, "Another org's feed is separate")
    }

    @Test
    fun publishingWithoutSubscriberDoesNothing() = runTest {
        feed.publish(modelEvent(5))
        val later = async(start = CoroutineStart.UNDISPATCHED) { feed.subscribe(TestCallers.orgA).first() }
        feed.publish(modelEvent(6))

        assertEquals(6L, later.await().costMicros, "Only events published after the subscription")
    }

    @Test
    fun thePurgeDeletesInBatchesUntilABatchIsNotFull() = runTest {
        repository.events += List(5) { modelEvent(1, at = TestCallers.epoch - 401.days) }
        repository.events += modelEvent(2, at = TestCallers.epoch - 399.days)
        val purge = PurgeUsageUseCaseImpl(repository, clock, 400.days, batchSize = 2)

        assertEquals(5L, purge())
        assertEquals(listOf(2L), repository.events.map { it.costMicros })
        assertEquals(listOf(2, 2, 2), repository.purges, "2 + 2 + 1: the third batch is not full")
    }

    @Test
    fun thePurgeStopsAfterItsMaximumNumberOfBatches() = runTest {
        repository.events += List(10) { modelEvent(1, at = TestCallers.epoch - 500.days) }
        val purge = PurgeUsageUseCaseImpl(repository, clock, 400.days, batchSize = 2, maxBatches = 3)

        assertEquals(6L, purge())
        assertEquals(4, repository.events.size, "The rest waits for the next run")
    }

    @Test
    fun thePurgeRunsHourlyAndSurvivesAFailure() = runTest {
        val logger = RecordingLogger()
        var runs = 0
        val purge = object : PurgeUsageUseCase {
            override suspend fun invoke(): Long {
                runs++
                if (runs == 1) error("database down")
                return 0
            }
        }
        val job = launch { UsageModule.purgeHourly(purge, logger) }
        runCurrent()
        assertEquals(1, runs)
        assertEquals(listOf(LogLevel.WARN to "usage purge failed"), logger.events)
        advanceTimeBy(1.hours + 1.seconds)
        assertEquals(2, runs)
        job.cancel()
    }

    @Test
    fun purgeHourlyWithCancellationPropagatesException() = runTest {
        val purge = object : PurgeUsageUseCase {
            override suspend fun invoke(): Long = throw CancellationException("stopping")
        }
        assertFailsWith<CancellationException> { UsageModule.purgeHourly(purge, RecordingLogger()) }
    }

    @Test
    fun theModuleStartsThePurgeInTheServerScope() = runTest {
        repository.events += modelEvent(1, at = TestCallers.epoch - 500.days)
        val koin = koinApplication {
            modules(
                module {
                    single<UsageRepository> { repository }
                    single<Clock> { clock }
                    single<Logger> { RecordingLogger() }
                    single<PurgeUsageUseCase> { PurgeUsageUseCaseImpl(get(), get(), 400.days) }
                },
            )
        }.koin
        UsageModule.start(backgroundScope, koin)
        runCurrent()

        assertTrue(repository.events.isEmpty(), "The first purge runs at start")
        koin.close()
    }

    @Test
    fun eventsOfTheSameInstantArePagedById() = runTest {
        val sameTime = List(3) { modelEvent(it.toLong()) }
        repository.events += sameTime
        val list = ListUsageEventsUseCaseImpl(repository)
        val first = list(TestCallers.adminA, null, 2)
        val rest = list(TestCallers.adminA, UsageCursor(first.last().at, first.last().id), 2)

        assertEquals(sameTime.map(UsageEvent::id).toSet(), (first + rest).map { it.id }.toSet())
        assertEquals(1, rest.size)
    }

    @Test
    fun aRetentionInThePastKeepsNothingOlder() = runTest {
        repository.events += modelEvent(1, at = Instant.parse("2025-01-01T00:00:00Z"))
        assertEquals(1L, PurgeUsageUseCaseImpl(repository, clock, 400.days)())
    }
}
