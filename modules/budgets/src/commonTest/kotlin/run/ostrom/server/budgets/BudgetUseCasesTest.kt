// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.BudgetAction
import run.ostrom.server.budgets.domain.BudgetAlert
import run.ostrom.server.budgets.domain.BudgetDecision
import run.ostrom.server.budgets.domain.BudgetGuard
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetPeriod
import run.ostrom.server.budgets.domain.BudgetRules
import run.ostrom.server.budgets.domain.BudgetScope
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.GetBudgetsUseCaseImpl
import run.ostrom.server.budgets.domain.GetMyBudgetUseCaseImpl
import run.ostrom.server.budgets.domain.InvalidBudgetException
import run.ostrom.server.budgets.domain.MemberBudget
import run.ostrom.server.budgets.domain.MyBudget
import run.ostrom.server.budgets.domain.NoBudgetAlerts
import run.ostrom.server.budgets.domain.SetBudgetUseCaseImpl
import run.ostrom.server.budgets.presentation.BudgetDto
import run.ostrom.server.budgets.presentation.toPage
import run.ostrom.server.budgets.presentation.toResponse
import run.ostrom.server.secrets.domain.AdminRequiredException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class BudgetUseCasesTest {
    private val clock = TestClock()
    private val repository = InMemoryBudgetsRepository()
    private val spend = FakeSpendReader()
    private val getBudgets = GetBudgetsUseCaseImpl(repository, spend, clock)
    private val set = SetBudgetUseCaseImpl(repository, clock, getBudgets)
    private val member = TestCallers.memberA.memberId

    @Test
    fun anAdminSetsTheOrgAndDefaultLimitsAndSeesTheMonthsSpend() = runTest {
        spend.spent[TestCallers.orgA to null] = 1_234

        set(TestCallers.adminA, BudgetTarget.Org, listOf(BudgetLimit(50_000_000)))
        val overview = set(TestCallers.adminA, BudgetTarget.DefaultMember, listOf(BudgetLimit(10_000_000)))

        assertEquals(50_000_000L, overview.settings.orgLimitMicros)
        assertEquals(10_000_000L, overview.settings.defaultMemberLimitMicros)
        assertEquals(1_234L, overview.orgSpentMicros)
        assertEquals(BudgetPeriod(TestCallers.monthStart, TestCallers.nextMonth), overview.period)

        set(TestCallers.adminA, BudgetTarget.Org, emptyList())
        val cleared = set(TestCallers.adminA, BudgetTarget.DefaultMember, emptyList())
        assertEquals(null, cleared.settings.orgLimitMicros)
        assertEquals(null, cleared.settings.defaultMemberLimitMicros)
    }

    @Test
    fun limitsAreZeroToOneBillionDollars() = runTest {
        assertFailsWith<InvalidBudgetException> { set(TestCallers.adminA, BudgetTarget.Org, listOf(BudgetLimit(-1))) }
        assertFailsWith<InvalidBudgetException> {
            set(TestCallers.adminA, BudgetTarget.DefaultMember, listOf(BudgetLimit(BudgetRules.MAX_LIMIT_MICROS + 1)))
        }
        assertFailsWith<InvalidBudgetException> {
            set(TestCallers.adminA, BudgetTarget.Member(member), listOf(BudgetLimit(-5)))
        }
        assertEquals(0L, set(TestCallers.adminA, BudgetTarget.Org, listOf(BudgetLimit(0))).settings.orgLimitMicros)
        val most = set(TestCallers.adminA, BudgetTarget.Org, listOf(BudgetLimit(BudgetRules.MAX_LIMIT_MICROS)))
        assertEquals(BudgetRules.MAX_LIMIT_MICROS, most.settings.orgLimitMicros)
        assertEquals(emptyList(), repository.memberLimits.values.toList())
    }

    @Test
    fun onlyAdminsReadOrChangeTheBudgets() = runTest {
        assertFailsWith<AdminRequiredException> { getBudgets(TestCallers.memberA) }
        assertFailsWith<AdminRequiredException> {
            set(TestCallers.memberA, BudgetTarget.Member(member), listOf(BudgetLimit(1)))
        }
        assertFailsWith<AdminRequiredException> { set(TestCallers.memberA, BudgetTarget.Member(member), emptyList()) }
    }

    @Test
    fun anAdminGivesAMemberTheirOwnLimitThenRemovesIt() = runTest {
        val given = set(TestCallers.adminA, BudgetTarget.Member(member), listOf(BudgetLimit(3_000_000)))

        assertEquals(listOf(MemberBudget(member, 3_000_000, TestCallers.epoch)), given.settings.overrides)
        set(TestCallers.adminA, BudgetTarget.Member(member), emptyList())
        assertEquals(emptyList(), getBudgets(TestCallers.adminA).settings.overrides)
    }

    @Test
    fun aMemberReadsTheirLimitSpendAndTheGatewaysDecision() = runTest {
        val decisions = mutableListOf<BudgetDecision>(
            BudgetDecision.Exhausted(
                BudgetScope.ORG,
                9,
                TestCallers.nextMonth,
            ),
        )
        val guard = object : BudgetGuard {
            override suspend fun check(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetDecision =
                decisions.removeFirst()
        }
        val mine = GetMyBudgetUseCaseImpl(repository, spend, guard, clock)
        repository.defaultLimits[TestCallers.orgA] = 1_000
        spend.spent[TestCallers.orgA to member] = 1_500

        val budget = mine(TestCallers.memberA)

        assertEquals(
            MyBudget(
                1_000,
                1_500,
                0,
                TestCallers.nextMonth,
                BudgetDecision.Exhausted(BudgetScope.ORG, 9, TestCallers.nextMonth),
            ),
            budget,
            "Remaining never goes negative",
        )
        assertEquals(TestCallers.monthStart, spend.sinces.single())
        val response = budget.toResponse()
        assertEquals(true, response.exhausted)
        assertEquals("org", response.exhaustedScope)
        assertEquals("2026-11-01T00:00:00Z", response.resetsAt)
    }

    @Test
    fun aMemberWithoutLimitHasNoRemaining() = runTest {
        val guard = object : BudgetGuard {
            override suspend fun check(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetDecision =
                BudgetDecision.Allowed(null)
        }
        val budget = GetMyBudgetUseCaseImpl(repository, spend, guard, clock)(TestCallers.memberA)

        assertEquals(null, budget.limitMicros)
        assertEquals(null, budget.remainingMicros)
        val response = budget.toResponse()
        assertEquals(false, response.exhausted)
        assertEquals(null, response.exhaustedScope)
    }

    @Test
    fun theOverviewAndAnOverrideMapToTheApi() = runTest {
        set(TestCallers.adminA, BudgetTarget.Member(member), listOf(BudgetLimit(7)))
        val page = set(TestCallers.adminA, BudgetTarget.Org, listOf(BudgetLimit(1_000_000))).toPage()

        assertEquals(
            listOf(BudgetDto("organization", usdPerMonth = 1.0), BudgetDto("member", member.toString(), 7e-6)),
            page.items,
        )
        assertEquals(null, page.next)
        assertEquals("2026-10-01T00:00:00Z", page.periodStart)
        assertEquals("2026-11-01T00:00:00Z", page.resetsAt)
    }

    @Test
    fun aSubjectsLimitsListAsOneBudgetThatStopsOnlyIfTheyAllDo() = runTest {
        val day = BudgetLimit(2_000_000, BudgetWindow.DAY)
        set(TestCallers.adminA, BudgetTarget.Org, listOf(BudgetLimit(10_000_000), day))
        set(TestCallers.adminA, BudgetTarget.DefaultMember, listOf(day.copy(action = BudgetAction.WARN)))
        val warns = listOf(BudgetLimit(1, action = BudgetAction.WARN))
        val page = set(TestCallers.adminA, BudgetTarget.Member(member), warns).toPage()
        assertEquals(
            listOf(
                BudgetDto("organization", usdPerMonth = 10.0, usdPerDay = 2.0),
                BudgetDto("member", usdPerDay = 2.0, hardStop = false),
                BudgetDto("member", member.toString(), usdPerMonth = 1e-6, hardStop = false),
            ),
            page.items,
        )
    }

    @Test
    fun settingOneSubjectsLimitsLeavesTheOthersAsAnotherAdminJustSetThem() = runTest {
        repository.defaultLimits[TestCallers.orgA] = 5_000_000
        val org = set(TestCallers.adminA, BudgetTarget.Org, listOf(BudgetLimit(7_000_000)))
        assertEquals(7_000_000, org.settings.orgLimitMicros)
        assertEquals(5_000_000, repository.defaultLimits[TestCallers.orgA], "Never read and written back")
        repository.orgLimits[TestCallers.orgA] = 9_000_000
        val removed = set(TestCallers.adminA, BudgetTarget.DefaultMember, emptyList())
        assertEquals(emptyList(), removed.settings.defaultMember)
        assertEquals(9_000_000, repository.orgLimits[TestCallers.orgA])
        assertFailsWith<InvalidBudgetException> { set(TestCallers.adminA, BudgetTarget.Org, listOf(BudgetLimit(-1))) }
        assertFailsWith<AdminRequiredException> { set(TestCallers.memberA, BudgetTarget.Org, listOf(BudgetLimit(1))) }

        val daily = BudgetLimit(2, BudgetWindow.DAY, BudgetAction.WARN)
        set(TestCallers.adminA, BudgetTarget.Member(member), listOf(BudgetLimit(3), daily))
        assertEquals(3, repository.memberLimits[TestCallers.orgA to member]?.limitMicros)
        assertEquals(listOf(daily), repository.otherLimits[TestCallers.orgA to BudgetTarget.Member(member)])
        set(TestCallers.adminA, BudgetTarget.Member(member), listOf(daily))
        assertEquals(null, repository.memberLimits[TestCallers.orgA to member], "Replaced whole: no more monthly limit")
        set(TestCallers.adminA, BudgetTarget.Member(member), emptyList())
        set(TestCallers.adminA, BudgetTarget.Member(member), emptyList())
        assertEquals(null, repository.otherLimits[TestCallers.orgA to BudgetTarget.Member(member)], "Removing twice")
    }

    @Test
    fun theDefaultAlertsPortDropsAlerts() = runTest {
        NoBudgetAlerts().publish(BudgetAlert(TestCallers.orgA, null, BudgetScope.ORG, 80, 1, 1, TestCallers.monthStart))
    }
}
