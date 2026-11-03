// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BUDGETS_ACTOR
import dev.ostrom.server.budgets.domain.AuditedBudgetAlerts
import dev.ostrom.server.budgets.domain.BudgetAction
import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.budgets.domain.BudgetCacheSettings
import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetGuardImpl
import dev.ostrom.server.budgets.domain.BudgetLimit
import dev.ostrom.server.budgets.domain.BudgetPeriod
import dev.ostrom.server.budgets.domain.BudgetScope
import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.domain.BudgetWindow
import dev.ostrom.server.budgets.domain.MemberBudget
import dev.ostrom.server.budgets.domain.monthlyMicros
import dev.ostrom.server.kernel.audit.OrgAuditRecord
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.testing.checkTime
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
    fun aZeroLimitThatWarnsAlertsAndIsAuditedFromTheFirstCent() = runTest {
        val audit = mutableListOf<OrgAuditRecord>()
        val audited = AuditedBudgetAlerts(alerts, OrgAuditTrail { audit += it })
        val guard = BudgetGuardImpl(repository, spend, audited, clock, logger)
        repository.otherLimits[orgA to BudgetTarget.Org] = listOf(BudgetLimit(0, action = BudgetAction.WARN))
        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member), "A limit that warns never refuses")
        assertEquals(emptyList(), alerts.alerts, "Nothing spent, nothing to say")

        spend.spent[orgA to null] = 10_000
        clock.advance(5.seconds)
        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member))
        assertEquals(listOf(80, 100), alerts.alerts.map { it.thresholdPercent }, "The first cent is past nothing")
        assertEquals(listOf("0", "0.01"), audit.single().details.let { listOf(it["limit_usd"], it["spent_usd"]) })
    }

    @Test
    fun aZeroLimitThatStopsAlertsWhatGotPastIt() = runTest {
        overrideMember(0)
        spend.spent[orgA to member] = 10_000

        assertEquals(BudgetDecision.Exhausted(BudgetScope.MEMBER, 0, TestCallers.nextMonth), guard.check(orgA, member))
        assertEquals(listOf(80, 100), alerts.alerts.map { it.thresholdPercent })
        assertEquals(listOf(10_000L, 10_000L), alerts.alerts.map { it.spentMicros })
    }

    @Test
    fun aLimitOfOneMillionthSaysNothingWhileNothingIsSpent() = runTest {
        overrideMember(1)
        assertEquals(BudgetDecision.Allowed(1), guard.check(orgA, member))
        assertEquals(emptyList(), alerts.alerts, "80 % of one millionth rounds to 0, which nothing spent is not past")

        spend.spent[orgA to member] = 1
        clock.advance(5.seconds)
        guard.check(orgA, member)
        assertEquals(listOf(80, 100), alerts.alerts.map { it.thresholdPercent })
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
    fun tenThousandCachedChecksReadTheLimitsOnceAndKeepTheirTimeBudget() = runTest {
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

        // What the Build holds, on any runner: 60 000 checks read the limits once, a check that reads the database
        // instead of the cache reads them every time. The time, 20 µs a check at most under Kover's instrumentation
        // (about 6 µs measured on a shared runner), is held by checkTime: the Build's summary and the Perf workflow.
        assertEquals(1, repository.limitReads, "Read once, then cached")
        checkTime("10 000 cached budget checks (BudgetGuard), best of 5", elapsed, 200.milliseconds)
    }

    @Test
    fun aDailyLimitThatStopsRefusesUntilTomorrowAndTheNearestLimitIsLeft() = runTest {
        val today = Instant.parse("2026-10-30T00:00:00Z")
        val tomorrow = Instant.parse("2026-10-31T00:00:00Z")
        repository.defaultLimits[orgA] = 10_000
        repository.otherLimits[orgA to BudgetTarget.DefaultMember] = listOf(BudgetLimit(1_000, BudgetWindow.DAY))
        spend.spent[orgA to member] = 600
        assertEquals(BudgetDecision.Allowed(400), guard.check(orgA, member), "The day leaves 400, the month 9 400")
        assertEquals(listOf(TestCallers.monthStart, today), spend.sinces)

        spend.spent[orgA to member] = 1_000
        clock.advance(5.seconds)
        assertEquals(
            BudgetDecision.Exhausted(BudgetScope.MEMBER, 1_000, tomorrow, BudgetWindow.DAY),
            guard.check(orgA, member),
        )
        assertEquals(today, alerts.alerts.last().periodStart)
        assertEquals(BudgetWindow.DAY, alerts.alerts.last().window)
    }

    @Test
    fun aLimitThatWarnsNeverRefusesAndIsAuditedOnceReached() = runTest {
        val audit = mutableListOf<OrgAuditRecord>()
        val audited = AuditedBudgetAlerts(alerts, OrgAuditTrail { audit += it })
        val guard = BudgetGuardImpl(repository, spend, audited, clock, logger)
        repository.otherLimits[orgA to BudgetTarget.Org] = listOf(BudgetLimit(1_000, action = BudgetAction.WARN))
        spend.spent[orgA to null] = 900
        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member), "No limit that stops")
        assertEquals(emptyList(), audit, "80 % is only an alert")

        spend.spent[orgA to null] = 1_500
        clock.advance(5.seconds)
        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member), "Past it, the call still goes")
        clock.advance(5.seconds)
        guard.check(orgA, admin)
        val line = audit.single()
        assertEquals(BUDGETS_ACTOR, line.actor)
        assertEquals("budget.exceeded" to "organization", line.event to line.subjectId)
        assertEquals(
            mapOf(
                "scope" to "organization", "period" to "month", "action" to "warn", "limit_usd" to "0.001",
                "spent_usd" to "0.0015", "period_start" to "2026-10-01T00:00:00Z",
            ),
            line.details,
        )
        assertEquals(listOf(BudgetAction.WARN, BudgetAction.WARN), alerts.alerts.map { it.action })

        // A limit that stops, reached, is refused by the gateway: no audit line.
        audited.publish(alerts.alerts.last().copy(action = BudgetAction.BLOCK))
        assertEquals(1, audit.size)
    }

    @Test
    fun anOverrunTheAuditLogCannotWriteIsNotSentEitherAndIsTriedAgainLater() = runTest {
        var down = true
        val audit = mutableListOf<OrgAuditRecord>()
        val audited = AuditedBudgetAlerts(alerts, OrgAuditTrail { if (down) error("audit log down") else audit += it })
        val guard = BudgetGuardImpl(repository, spend, audited, clock, logger)
        repository.otherLimits[orgA to BudgetTarget.Org] = listOf(BudgetLimit(1_000, action = BudgetAction.WARN))
        spend.spent[orgA to null] = 1_000
        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member))
        assertEquals(emptyList(), alerts.alerts.filter { it.thresholdPercent == 100 }, "Nothing sent without its line")

        down = false
        clock.advance(5.seconds)
        guard.check(orgA, member)
        assertEquals("0.001", audit.single().details["limit_usd"])
        assertEquals(1, alerts.alerts.count { it.thresholdPercent == 100 })

        // A member's daily limit that warns is written under the member.
        val daily = BudgetAlert(
            orgA, member, BudgetScope.MEMBER, 100, 2_000_000, 2_500_000, TestCallers.monthStart,
            BudgetWindow.DAY, BudgetAction.WARN,
        )
        audited.publish(daily)
        val line = audit.last()
        assertEquals(member.toString(), line.subjectId)
        assertEquals(listOf("member", "day", "2", "2.5"), listOf("scope", "period", "limit_usd", "spent_usd").map {
            line.details[it]
        })
        assertEquals(null, listOf(BudgetLimit(1, BudgetWindow.DAY)).monthlyMicros(), "No monthly amount")
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
