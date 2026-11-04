// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAction
import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetGuard
import dev.ostrom.server.budgets.domain.BudgetLimit
import dev.ostrom.server.budgets.domain.BudgetScope
import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.domain.BudgetWindow
import dev.ostrom.server.budgets.domain.LedgerSpendGuard
import dev.ostrom.server.gateway.domain.SpendDecision
import dev.ostrom.server.gateway.domain.SpendRequest
import dev.ostrom.server.usage.domain.Admission
import dev.ostrom.server.usage.domain.DailySpendLimits
import dev.ostrom.server.usage.domain.SpendAdmission
import dev.ostrom.server.usage.domain.SpendHold
import dev.ostrom.server.usage.domain.SpendLimits
import dev.ostrom.server.usage.domain.SpendScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The gateway's guard on the limits: no limit goes free, a reached limit refuses fast, the ledger admits the rest. */
class LedgerSpendGuardTest {
    private val clock = TestClock()
    private val repository = InMemoryBudgetsRepository()
    private val orgA = TestCallers.orgA
    private val member = TestCallers.memberA.memberId
    private val callId = Uuid.random()
    private val nextMonth = Instant.parse("2026-11-01T00:00:00Z")
    private val monthStart = TestCallers.monthStart

    private var cached: BudgetDecision = BudgetDecision.Allowed(null)
    private val guardCalls = mutableListOf<Pair<Uuid, Uuid>>()
    private val guardWorkflows = mutableListOf<String?>()
    private val guard = object : BudgetGuard {
        override suspend fun check(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetDecision {
            guardCalls += orgId to memberId
            guardWorkflows += workflowId
            return cached
        }
    }

    private var answer: Admission = Admission.Admitted
    private val admitted = mutableListOf<Triple<SpendHold, SpendLimits, Instant>>()
    private val admission = object : SpendAdmission {
        override suspend fun admit(hold: SpendHold, limits: SpendLimits, since: Instant): Admission {
            admitted += Triple(hold, limits, since)
            return answer
        }
    }

    private val spendGuard = LedgerSpendGuard(repository, guard, admission, clock)

    private suspend fun check(reserve: Long = 1_234_567, workflowId: String? = null) =
        spendGuard.check(SpendRequest(orgA, member, callId, reserve, workflowId))

    @Test
    fun withoutALimitACallGoesOnTheCachedDecisionAlone() = runTest {
        assertEquals(SpendDecision.Allowed, check())
        assertEquals(listOf(orgA to member), guardCalls)
        assertEquals(emptyList(), admitted, "No lock, no hold")
        assertEquals(0, repository.limitReads, "Not even a read of the limits")
    }

    @Test
    fun withALimitTheLedgerAdmitsTheCallAgainstFreshLimitsAndTheMonth() = runTest {
        cached = BudgetDecision.Allowed(2_000_000)
        repository.defaultLimits[orgA] = 2_000_000
        assertEquals(SpendDecision.Allowed, check())
        assertEquals(
            listOf(Triple(SpendHold(orgA, member, callId, 1_234_567), SpendLimits(null, 2_000_000), monthStart)),
            admitted,
        )
        repository.orgLimits[orgA] = 5_000_000
        check()
        assertEquals(SpendLimits(5_000_000, 2_000_000), admitted.last().second, "Read again for each call")
    }

    @Test
    fun aReachedLimitIsRefusedUntilTheMonthEnds() = runTest {
        repository.orgLimits[orgA] = 100_000_000
        cached = BudgetDecision.Exhausted(BudgetScope.ORG, 100_000_000, nextMonth)
        assertEquals(
            SpendDecision.Exhausted(
                "Monthly budget reached: your organization's limit of $100.00 is spent; it resets on 2026-11-01.",
                nextMonth,
            ),
            check(),
        )
        assertEquals(emptyList(), admitted, "Known reached from the cache: no lock is taken")

        cached = BudgetDecision.Exhausted(BudgetScope.MEMBER, 2_500_000, nextMonth)
        assertEquals(
            "Monthly budget reached: your limit of $2.50 is spent; it resets on 2026-11-01.",
            (check() as SpendDecision.Exhausted).message,
        )

        cached = BudgetDecision.Allowed(1)
        answer = Admission.Exhausted(SpendScope.MEMBER, 2_050_000)
        assertEquals(
            SpendDecision.Exhausted(
                "Monthly budget reached: your limit of $2.05 is spent; it resets on 2026-11-01.",
                nextMonth,
            ),
            check(),
            "Reached by the fresh sums of the ledger",
        )
    }

    @Test
    fun aCallTheBudgetLeftCannotCoverIsRefusedWithWhatIsLeft() = runTest {
        repository.orgLimits[orgA] = 100_000_000
        cached = BudgetDecision.Allowed(400_000)
        answer = Admission.Insufficient(SpendScope.ORG, 100_000_000, 400_000)
        assertEquals(
            SpendDecision.Insufficient(
                "Budget left too low for this call: $0.40 is left of your organization's $100.00 monthly limit " +
                    "and this call may cost up to $12.35. Lower its max_tokens, or retry once the calls in flight " +
                    "finish.",
            ),
            check(reserve = 12_345_678),
            "What is left rounds down, what the call may cost rounds up",
        )
        assertTrue("up to $12.00." in (check(reserve = 12_000_000) as SpendDecision.Insufficient).message)
        answer = Admission.Insufficient(SpendScope.MEMBER, 1_000_000, 9_999)
        // A sum finer than a cent is written to the micro; a cent or more, or a dollar or more, to the cent.
        assertEquals(
            "Budget left too low for this call: $0.009999 is left of your $1.00 monthly limit and this call may cost " +
                "up to $0.01. Lower its max_tokens, or retry once the calls in flight finish.",
            (check(reserve = 10_000) as SpendDecision.Insufficient).message,
        )
    }

    @Test
    fun aCallOfALaunchThatPlaysAWorkflowIsAdmittedAgainstThatWorkflowsLimits() = runTest {
        val today = Instant.parse("2026-10-30T00:00:00Z")
        val tomorrow = Instant.parse("2026-10-31T00:00:00Z")
        cached = BudgetDecision.Allowed(1)
        repository.otherLimits[orgA to BudgetTarget.Workflow("review")] =
            listOf(BudgetLimit(3_000_000), BudgetLimit(500_000, BudgetWindow.DAY))
        repository.otherLimits[orgA to BudgetTarget.Workflow("release")] = listOf(BudgetLimit(1))
        check(workflowId = "review")
        assertEquals(listOf<String?>("review"), guardWorkflows, "The cached decision is the workflow's too")
        assertEquals(
            Triple(
                SpendHold(orgA, member, callId, 1_234_567, "review"),
                SpendLimits(null, null, DailySpendLimits(today, null, null, 500_000), 3_000_000),
                monthStart,
            ),
            admitted.last(),
            "Its own limits, not another workflow's",
        )
        check()
        assertEquals(SpendLimits(null, null), admitted.last().second, "A call that plays no workflow has none")

        repository.otherLimits[orgA to BudgetTarget.Workflow("review")] =
            listOf(BudgetLimit(3_000_000, action = BudgetAction.WARN))
        check(workflowId = "review")
        assertEquals(SpendLimits(null, null), admitted.last().second, "A workflow budget that warns stops nothing")

        answer = Admission.Exhausted(SpendScope.WORKFLOW, 3_000_000)
        assertEquals(
            SpendDecision.Exhausted(
                "Monthly budget reached: this workflow's limit of $3.00 is spent; it resets on 2026-11-01.",
                nextMonth,
            ),
            check(workflowId = "review"),
        )
        answer = Admission.Insufficient(SpendScope.WORKFLOW, 500_000, 100_000, daily = true)
        val short = check(workflowId = "review") as SpendDecision.Insufficient
        assertTrue("of this workflow's $0.50 daily limit" in short.message)
        cached = BudgetDecision.Exhausted(BudgetScope.WORKFLOW, 500_000, tomorrow, BudgetWindow.DAY)
        assertEquals(
            SpendDecision.Exhausted(
                "Daily budget reached: this workflow's limit of $0.50 a day is spent; it resets on 2026-10-31.",
                tomorrow,
            ),
            check(workflowId = "review"),
            "Known reached from the cache",
        )
    }

    @Test
    fun aDailyLimitThatStopsIsAdmittedOnTheDayAndOneThatWarnsIsLeftOut() = runTest {
        val today = Instant.parse("2026-10-30T00:00:00Z")
        val tomorrow = Instant.parse("2026-10-31T00:00:00Z")
        cached = BudgetDecision.Allowed(1)
        repository.otherLimits[orgA to BudgetTarget.Org] = listOf(BudgetLimit(900, BudgetWindow.DAY, BudgetAction.WARN))
        repository.otherLimits[orgA to BudgetTarget.DefaultMember] = listOf(BudgetLimit(300, BudgetWindow.DAY))
        check()
        assertEquals(SpendLimits(null, null, DailySpendLimits(today, null, 300)), admitted.last().second)
        repository.otherLimits[orgA to BudgetTarget.Org] = listOf(BudgetLimit(900, BudgetWindow.DAY))
        repository.otherLimits.remove(orgA to BudgetTarget.DefaultMember)
        check()
        assertEquals(SpendLimits(null, null, DailySpendLimits(today, 900, null)), admitted.last().second)

        answer = Admission.Exhausted(SpendScope.MEMBER, 300, daily = true)
        assertEquals(
            SpendDecision.Exhausted(
                "Daily budget reached: your limit of $0.0003 a day is spent; it resets on 2026-10-31.",
                tomorrow,
            ),
            check(),
        )
        answer = Admission.Insufficient(SpendScope.ORG, 1_000_000, 400_000, daily = true)
        assertTrue("of your organization's $1.00 daily limit" in (check() as SpendDecision.Insufficient).message)

        cached = BudgetDecision.Exhausted(BudgetScope.ORG, 2_000_000, tomorrow, BudgetWindow.DAY)
        assertEquals(
            SpendDecision.Exhausted(
                "Daily budget reached: your organization's limit of $2.00 a day is spent; it resets on 2026-10-31.",
                tomorrow,
            ),
            check(),
            "Known reached from the cache",
        )
    }
}
