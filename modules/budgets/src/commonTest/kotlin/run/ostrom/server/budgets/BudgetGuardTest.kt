// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.BUDGETS_ACTOR
import run.ostrom.server.budgets.domain.AuditedBudgetAlerts
import run.ostrom.server.budgets.domain.BudgetAction
import run.ostrom.server.budgets.domain.BudgetAlert
import run.ostrom.server.budgets.domain.BudgetDecision
import run.ostrom.server.budgets.domain.BudgetGuardImpl
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetPeriod
import run.ostrom.server.budgets.domain.BudgetScope
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.MemberBudget
import run.ostrom.server.budgets.domain.monthlyMicros
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
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
    fun everyCheckReadsTheSpendAsItIsNow() = runTest {
        overrideMember(1_000)
        spend.spent[orgA to member] = 100
        assertEquals(BudgetDecision.Allowed(900), guard.check(orgA, member))

        spend.spent[orgA to member] = 1_000
        assertEquals(
            BudgetDecision.Exhausted(BudgetScope.MEMBER, 1_000, TestCallers.nextMonth),
            guard.check(orgA, member),
            "In the same millisecond: nothing is cached",
        )
        assertEquals(2, repository.limitReads)
    }

    @Test
    fun aLimitSetOnAnotherReplicaAppliesToTheNextCheck() = runTest {
        // Another replica: its own guard, the same database.
        val elsewhere = BudgetGuardImpl(repository, spend, alerts, clock, logger)
        overrideMember(1_000)
        assertEquals(BudgetDecision.Allowed(1_000), guard.check(orgA, member))
        overrideMember(50)
        assertEquals(BudgetDecision.Allowed(50), elsewhere.check(orgA, member))
        assertEquals(BudgetDecision.Allowed(50), guard.check(orgA, member), "Applied at once, with no invalidation")
    }

    @Test
    fun theOrgSpendIsReadAtEveryCheck() = runTest {
        repository.orgLimits[orgA] = 1_000_000
        spend.spent[orgA to null] = 10

        guard.check(orgA, member)
        guard.check(orgA, admin)

        assertEquals(listOf<Pair<Uuid, Uuid?>>(orgA to null, orgA to null), spend.reads)
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
    fun aLimitChangedDuringACheckAppliesToTheNextOne() = runTest {
        repository.orgLimits[orgA] = 1_000_000
        spend.duringRead = { repository.orgLimits[orgA] = 0 }
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
    fun aWorkflowsLimitsApplyToTheCallsOfItsLaunchesOnlyAndAreAuditedUnderIt() = runTest {
        val audit = mutableListOf<OrgAuditRecord>()
        val audited = AuditedBudgetAlerts(alerts, OrgAuditTrail { audit += it })
        val guard = BudgetGuardImpl(repository, spend, audited, clock, logger)
        repository.otherLimits[orgA to BudgetTarget.Workflow("review")] = listOf(BudgetLimit(2_000))
        repository.otherLimits[orgA to BudgetTarget.Workflow("nightly")] =
            listOf(BudgetLimit(1_000, BudgetWindow.DAY, BudgetAction.WARN))
        spend.workflowSpent[orgA to "review"] = 2_000
        spend.workflowSpent[orgA to "nightly"] = 1_000
        spend.workflowSpent[TestCallers.orgB to "review"] = 0

        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member), "A call that plays no workflow")
        assertEquals(emptyList(), spend.workflowReads, "Its workflows' spend is not read")
        assertEquals(
            BudgetDecision.Exhausted(BudgetScope.WORKFLOW, 2_000, TestCallers.nextMonth),
            guard.check(orgA, member, "review"),
        )
        assertEquals(listOf(orgA to "review"), spend.workflowReads, "Read for its own workflow")
        assertEquals(
            BudgetDecision.Allowed(null),
            guard.check(TestCallers.orgB, TestCallers.adminB.memberId, "review"),
            "Org B's workflow of the same id has no limit",
        )
        val reached = alerts.alerts.last()
        assertEquals(
            listOf(BudgetScope.WORKFLOW, null, "review"),
            listOf(reached.scope, reached.memberId, reached.workflowId),
        )
        assertEquals(emptyList(), audit, "A workflow budget that stops is refused, not audited")

        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member, "nightly"), "One that warns lets it go")
        val line = audit.single()
        assertEquals("budget.exceeded" to "nightly", line.event to line.subjectId)
        assertEquals(listOf("workflow", "day", "warn"), listOf("scope", "period", "action").map { line.details[it] })
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
