// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.budgets.domain.BudgetCacheSettings
import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetGuardImpl
import dev.ostrom.server.budgets.domain.BudgetPeriod
import dev.ostrom.server.budgets.domain.BudgetScope
import dev.ostrom.server.budgets.domain.MemberBudget
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

class BudgetGuardTest {
    private val clock = TestClock()
    private val repository = InMemoryBudgetsRepository()
    private val spend = FakeSpendReader()
    private val alerts = RecordingAlerts()
    private val logger = RecordingLogger()
    private val guard = BudgetGuardImpl(repository, spend, alerts, clock, logger)
    private val orgA = TestCallers.orgA
    private val member = TestCallers.memberA.memberId
    private val admin = TestCallers.adminA.memberId

    private fun overrideMember(limit: Long) {
        repository.memberLimits[orgA to member] = MemberBudget(member, limit, TestCallers.epoch)
    }

    @Test
    fun withoutAnyLimitEverythingIsAllowedAndNoSpendIsRead() = runTest {
        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member))
        assertEquals(emptyList(), spend.reads, "Nothing to compare: the ledger is not read")
    }

    @Test
    fun theRemainingIsTheNearestLimit() = runTest {
        repository.orgLimits[orgA] = 10_000_000
        repository.defaultLimits[orgA] = 2_000_000
        spend.spent[orgA to null] = 9_500_000
        spend.spent[orgA to member] = 100_000

        assertEquals(
            BudgetDecision.Allowed(500_000),
            guard.check(orgA, member),
            "The org has 0.50 USD left, the member 1.90",
        )
        assertEquals(
            listOf(TestCallers.monthStart, TestCallers.monthStart),
            spend.sinces,
            "Both sums start on the 1st of the month",
        )
    }

    @Test
    fun aMemberOverrideReplacesTheDefault() = runTest {
        repository.defaultLimits[orgA] = 1_000_000
        overrideMember(5_000_000)
        spend.spent[orgA to member] = 2_000_000
        spend.spent[orgA to admin] = 1_000_000

        assertEquals(BudgetDecision.Allowed(3_000_000), guard.check(orgA, member))
        assertEquals(
            BudgetDecision.Exhausted(BudgetScope.MEMBER, 1_000_000, TestCallers.nextMonth),
            guard.check(orgA, admin),
            "The admin is on the default limit",
        )
    }

    @Test
    fun theOrgLimitWinsWhenBothAreReached() = runTest {
        repository.orgLimits[orgA] = 1_000
        repository.defaultLimits[orgA] = 1_000
        spend.spent[orgA to null] = 1_000
        spend.spent[orgA to member] = 1_000

        assertEquals(BudgetDecision.Exhausted(BudgetScope.ORG, 1_000, TestCallers.nextMonth), guard.check(orgA, member))
    }

    @Test
    fun aMemberIsStoppedAtTheirLimitWhileTheOrgHasRoom() = runTest {
        repository.orgLimits[orgA] = 1_000_000
        overrideMember(300)
        spend.spent[orgA to member] = 301

        assertEquals(
            BudgetDecision.Exhausted(BudgetScope.MEMBER, 300, TestCallers.nextMonth),
            guard.check(orgA, member),
        )
    }

    @Test
    fun aZeroLimitBlocksWithoutAlerting() = runTest {
        overrideMember(0)

        assertEquals(BudgetDecision.Exhausted(BudgetScope.MEMBER, 0, TestCallers.nextMonth), guard.check(orgA, member))
        assertEquals(emptyList(), alerts.alerts)
    }

    @Test
    fun aDecisionIsReusedForFiveSecondsThenRecomputed() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 100
        assertEquals(BudgetDecision.Allowed(900), guard.check(orgA, member))

        spend.spent[orgA to member] = 1_000
        clock.advance(4_999.milliseconds)
        assertEquals(BudgetDecision.Allowed(900), guard.check(orgA, member), "Cached")
        assertEquals(1, repository.limitReads)

        clock.advance(1.milliseconds)
        assertEquals(
            BudgetDecision.Exhausted(BudgetScope.MEMBER, 1_000, TestCallers.nextMonth),
            guard.check(orgA, member),
        )
        assertEquals(2, repository.limitReads)
    }

    @Test
    fun invalidatingAnOrgForgetsItsDecisionsOnly() = runTest {
        overrideMember(1_000)
        repository.orgLimits[TestCallers.orgB] = 1_000
        guard.check(orgA, member)
        guard.check(TestCallers.orgB, TestCallers.adminB.memberId)
        overrideMember(50)

        guard.invalidate(orgA)

        assertEquals(BudgetDecision.Allowed(50), guard.check(orgA, member), "The new limit applies at once")
        guard.check(TestCallers.orgB, TestCallers.adminB.memberId)
        assertEquals(3, repository.limitReads, "Org B stayed cached")
    }

    @Test
    fun theOrgSpendIsReadOncePerOrgForAllItsMembers() = runTest {
        repository.orgLimits[orgA] = 1_000_000
        spend.spent[orgA to null] = 10

        guard.check(orgA, member)
        guard.check(orgA, admin)

        assertEquals(listOf<Pair<Uuid, Uuid?>>(orgA to null), spend.reads)
        clock.advance(5.seconds)
        guard.check(orgA, member)
        assertEquals(2, spend.reads.size, "Read again once expired")
    }

    @Test
    fun crossingEightyThenOneHundredPercentAlertsOnceEach() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 799
        guard.check(orgA, member)
        assertEquals(emptyList(), alerts.alerts, "Under 80 %")

        spend.spent[orgA to member] = 800
        clock.advance(5.seconds)
        guard.check(orgA, member)
        clock.advance(5.seconds)
        guard.check(orgA, member)
        assertEquals(
            listOf(BudgetAlert(orgA, member, BudgetScope.MEMBER, 80, 1_000, 800, TestCallers.monthStart)),
            alerts.alerts,
            "Once, even if checked again",
        )

        spend.spent[orgA to member] = 1_200
        clock.advance(5.seconds)
        guard.check(orgA, member)
        assertEquals(listOf(80, 100), alerts.alerts.map { it.thresholdPercent })
        assertEquals(1_200L, alerts.alerts.last().spentMicros)
    }

    @Test
    fun jumpingPastBothThresholdsAlertsBothAndTheOrgAlertHasNoMember() = runTest {
        repository.orgLimits[orgA] = 1_000
        spend.spent[orgA to null] = 5_000

        guard.check(orgA, member)

        assertEquals(listOf(80, 100), alerts.alerts.map { it.thresholdPercent })
        assertTrue(alerts.alerts.all { it.scope == BudgetScope.ORG && it.memberId == null })
    }

    @Test
    fun anAlertAnotherReplicaAlreadySentIsNotSentAgain() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 900
        repository.markAlerted(
            BudgetAlert(orgA, member, BudgetScope.MEMBER, 80, 1_000, 850, TestCallers.monthStart),
            TestCallers.epoch,
        )

        guard.check(orgA, member)

        assertEquals(emptyList(), alerts.alerts)
    }

    @Test
    fun aFailedAlertIsLoggedAndRetriedByALaterCheck() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 900
        repository.markFailure = IllegalStateException("database down")

        assertEquals(
            BudgetDecision.Allowed(100),
            guard.check(orgA, member),
            "The decision does not depend on the alert",
        )
        assertEquals(listOf(LogLevel.WARN to "budget alert failed"), logger.events)

        repository.markFailure = null
        clock.advance(5.seconds)
        guard.check(orgA, member)
        assertEquals(listOf(80), alerts.alerts.map { it.thresholdPercent })
    }

    @Test
    fun aFailedPublicationIsLogged() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 900
        alerts.failure = IllegalStateException("smtp down")

        guard.check(orgA, member)

        assertEquals(listOf(LogLevel.WARN to "budget alert failed"), logger.events)
    }

    @Test
    fun aFailedPublicationIsRetriedByALaterCheck() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 900
        alerts.failure = IllegalStateException("smtp down")
        guard.check(orgA, member)

        alerts.failure = null
        clock.advance(5.seconds)
        guard.check(orgA, member)

        assertEquals(listOf(80), alerts.alerts.map { it.thresholdPercent }, "The mark was taken back: it is sent now")
    }

    @Test
    fun aCancelledAlertIsNotTakenAsSent() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 900
        repository.markFailure = CancellationException("gateway call cancelled")
        assertFailsWith<CancellationException> { guard.check(orgA, member) }

        repository.markFailure = null
        guard.check(orgA, member)

        assertEquals(listOf(80), alerts.alerts.map { it.thresholdPercent })
    }

    @Test
    fun aCancelledPublicationTakesBackItsMark() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 900
        alerts.failure = CancellationException("gateway call cancelled")
        assertFailsWith<CancellationException> { guard.check(orgA, member) }

        alerts.failure = null
        guard.check(orgA, member)

        assertEquals(listOf(80), alerts.alerts.map { it.thresholdPercent })
    }

    @Test
    fun aLimitChangedDuringACheckIsNotCachedOverByIt() = runTest {
        repository.orgLimits[orgA] = 1_000_000
        spend.duringRead = {
            repository.orgLimits[orgA] = 0
            guard.invalidate(orgA)
        }
        assertEquals(BudgetDecision.Allowed(1_000_000), guard.check(orgA, member), "Read before the change")

        assertEquals(
            BudgetDecision.Exhausted(BudgetScope.ORG, 0, TestCallers.nextMonth),
            guard.check(orgA, member),
            "The next check, in the same second, sees the new limit",
        )
    }

    @Test
    fun aChangedLimitAlertsAgainInTheSameMonth() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 900
        guard.check(orgA, member)

        overrideMember(1_100)
        guard.invalidate(orgA)
        guard.check(orgA, member)

        assertEquals(listOf(1_000L, 1_100L), alerts.alerts.map { it.limitMicros })
    }

    @Test
    fun checkWithCancellationPropagatesException() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 900
        repository.markFailure = CancellationException("gateway call cancelled")

        assertFailsWith<CancellationException> { guard.check(orgA, member) }
    }

    @Test
    fun aNewMonthAlertsAgainAndForgetsTheOldOnes() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 900
        guard.check(orgA, member)

        clock.now = Instant.parse("2026-11-02T00:00:00Z")
        guard.check(orgA, member)

        assertEquals(listOf(TestCallers.monthStart, TestCallers.nextMonth), alerts.alerts.map { it.periodStart })
    }

    @Test
    fun theCacheStaysBoundedDroppingExpiredEntriesFirst() = runTest {
        val small = BudgetGuardImpl(repository, spend, alerts, clock, logger, BudgetCacheSettings(maxEntries = 2))
        val members = List(3) { Uuid.random() }
        small.check(orgA, members[0])
        clock.advance(5.seconds)
        small.check(orgA, members[1])
        small.check(orgA, members[2])
        assertEquals(3, repository.limitReads, "The expired entry made room")

        small.check(orgA, members[1])
        assertEquals(3, repository.limitReads, "Still cached")
        small.check(orgA, members[0])
        assertEquals(4, repository.limitReads, "Full of live entries: the cache was cleared")
        small.check(orgA, members[1])
        assertEquals(5, repository.limitReads)
    }

    @Test
    fun tenThousandCachedChecksTakeUnderTwoHundredMilliseconds() = runTest {
        repository.orgLimits[orgA] = 1_000_000
        overrideMember(10_000)
        // An untimed pass first: on the JVM the first calls also pay class loading and the JIT, not the cache.
        repeat(10_000) { guard.check(orgA, member) }

        // The best of five passes: the cost of the cache, not a pause of the shared CI runner.
        val elapsed = List(5) {
            val mark = TimeSource.Monotonic.markNow()
            repeat(10_000) { guard.check(orgA, member) }
            mark.elapsedNow()
        }.min()

        // 20 µs a check at most, under Kover's instrumentation on a shared runner (about 6 µs measured there): a check
        // that reads the database instead of the cache takes milliseconds, two orders of magnitude above.
        assertTrue(elapsed < 200.milliseconds, "10 000 cached checks took $elapsed")
        assertEquals(1, repository.limitReads)
    }

    @Test
    fun theMonthIsTheUtcCalendarMonth() {
        assertEquals(
            BudgetPeriod(TestCallers.monthStart, TestCallers.nextMonth),
            BudgetPeriod.of(Instant.parse("2026-10-31T23:59:59.999Z")),
        )
        assertEquals(
            BudgetPeriod(Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")),
            BudgetPeriod.of(Instant.parse("2026-12-15T08:00:00Z")),
        )
        assertEquals(
            BudgetPeriod(Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-02-01T00:00:00Z")),
            BudgetPeriod.of(Instant.parse("2027-01-01T00:00:00Z")),
        )
        assertEquals(
            BudgetPeriod(Instant.parse("2028-02-01T00:00:00Z"), Instant.parse("2028-03-01T00:00:00Z")),
            BudgetPeriod.of(Instant.parse("2028-02-29T12:00:00Z")),
            "A leap February",
        )
        assertEquals(
            BudgetPeriod(Instant.parse("1969-12-01T00:00:00Z"), Instant.parse("1970-01-01T00:00:00Z")),
            BudgetPeriod.of(Instant.parse("1969-12-31T23:00:00Z")),
            "Before the epoch",
        )
        assertEquals(
            BudgetPeriod(Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z")),
            BudgetPeriod.of(Instant.parse("2026-03-01T00:00:00Z") + 1.days),
        )
    }
}
