// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetGuard
import dev.ostrom.server.budgets.domain.BudgetPeriod
import dev.ostrom.server.budgets.domain.BudgetRules
import dev.ostrom.server.budgets.domain.BudgetScope
import dev.ostrom.server.budgets.domain.DeleteMemberBudgetUseCaseImpl
import dev.ostrom.server.budgets.domain.GetBudgetsUseCaseImpl
import dev.ostrom.server.budgets.domain.GetMyBudgetUseCaseImpl
import dev.ostrom.server.budgets.domain.InvalidBudgetException
import dev.ostrom.server.budgets.domain.MemberBudget
import dev.ostrom.server.budgets.domain.MemberBudgetNotFoundException
import dev.ostrom.server.budgets.domain.MyBudget
import dev.ostrom.server.budgets.domain.NoBudgetAlerts
import dev.ostrom.server.budgets.domain.PutBudgetsUseCaseImpl
import dev.ostrom.server.budgets.domain.PutMemberBudgetUseCaseImpl
import dev.ostrom.server.budgets.presentation.toResponse
import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.secrets.domain.AdminRequiredException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class BudgetUseCasesTest {
    private val clock = TestClock()
    private val repository = InMemoryBudgetsRepository()
    private val spend = FakeSpendReader()
    private val cache = RecordingCache()
    private val getBudgets = GetBudgetsUseCaseImpl(repository, spend, clock)
    private val putBudgets = PutBudgetsUseCaseImpl(repository, cache, getBudgets, clock)
    private val putMember = PutMemberBudgetUseCaseImpl(repository, cache, clock)
    private val deleteMember = DeleteMemberBudgetUseCaseImpl(repository, cache)
    private val member = TestCallers.memberA.memberId

    @Test
    fun anAdminSetsTheOrgAndDefaultLimitsAndSeesTheMonthsSpend() = runTest {
        spend.spent[TestCallers.orgA to null] = 1_234

        val overview = putBudgets(TestCallers.adminA, 50_000_000, 10_000_000)

        assertEquals(50_000_000L, overview.settings.orgLimitMicros)
        assertEquals(10_000_000L, overview.settings.defaultMemberLimitMicros)
        assertEquals(1_234L, overview.orgSpentMicros)
        assertEquals(BudgetPeriod(TestCallers.monthStart, TestCallers.nextMonth), overview.period)
        assertEquals(listOf(TestCallers.orgA), cache.invalidated, "The guard forgets the old limits")

        val cleared = putBudgets(TestCallers.adminA, null, null)
        assertEquals(null, cleared.settings.orgLimitMicros)
        assertEquals(null, cleared.settings.defaultMemberLimitMicros)
    }

    @Test
    fun limitsAreZeroToOneBillionDollars() = runTest {
        assertFailsWith<InvalidBudgetException> { putBudgets(TestCallers.adminA, -1, null) }
        assertFailsWith<InvalidBudgetException> { putBudgets(TestCallers.adminA, null, BudgetRules.MAX_LIMIT_MICROS + 1) }
        assertFailsWith<InvalidBudgetException> { putMember(TestCallers.adminA, member, -5) }
        assertEquals(0L, putBudgets(TestCallers.adminA, 0, BudgetRules.MAX_LIMIT_MICROS).settings.orgLimitMicros)
        assertEquals(emptyList(), repository.memberLimits.values.toList())
    }

    @Test
    fun onlyAdminsReadOrChangeTheBudgets() = runTest {
        assertFailsWith<AdminRequiredException> { getBudgets(TestCallers.memberA) }
        assertFailsWith<AdminRequiredException> { putBudgets(TestCallers.memberA, 1, 1) }
        assertFailsWith<AdminRequiredException> { putMember(TestCallers.memberA, member, 1) }
        assertFailsWith<AdminRequiredException> { deleteMember(TestCallers.memberA, member) }
        assertEquals(emptyList(), cache.invalidated)
    }

    @Test
    fun anAdminGivesAMemberTheirOwnLimitThenRemovesIt() = runTest {
        val budget = putMember(TestCallers.adminA, member, 3_000_000)

        assertEquals(MemberBudget(member, 3_000_000, TestCallers.epoch), budget)
        assertEquals(listOf(budget), getBudgets(TestCallers.adminA).settings.overrides)
        deleteMember(TestCallers.adminA, member)
        assertEquals(emptyList(), getBudgets(TestCallers.adminA).settings.overrides)
        assertEquals(listOf(TestCallers.orgA, TestCallers.orgA), cache.invalidated)
        assertFailsWith<MemberBudgetNotFoundException> { deleteMember(TestCallers.adminA, member) }
    }

    @Test
    fun aMemberReadsTheirLimitSpendAndTheGatewaysDecision() = runTest {
        val decisions = mutableListOf<BudgetDecision>(BudgetDecision.Exhausted(BudgetScope.ORG, 9, TestCallers.nextMonth))
        val guard = object : BudgetGuard {
            override suspend fun check(orgId: Uuid, memberId: Uuid): BudgetDecision = decisions.removeFirst()
        }
        val mine = GetMyBudgetUseCaseImpl(repository, spend, guard, clock)
        repository.defaultLimits[TestCallers.orgA] = 1_000
        spend.spent[TestCallers.orgA to member] = 1_500

        val budget = mine(TestCallers.memberA)

        assertEquals(
            MyBudget(1_000, 1_500, 0, TestCallers.nextMonth, BudgetDecision.Exhausted(BudgetScope.ORG, 9, TestCallers.nextMonth)),
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
            override suspend fun check(orgId: Uuid, memberId: Uuid): BudgetDecision = BudgetDecision.Allowed(null)
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
        putMember(TestCallers.adminA, member, 7)
        val response = putBudgets(TestCallers.adminA, 100, null).toResponse()

        assertEquals(100L, response.orgLimitMicros)
        assertEquals(null, response.defaultMemberLimitMicros)
        assertEquals(listOf(member.toString()), response.overrides.map { it.memberId })
        assertEquals("2026-10-30T12:00:00Z", response.overrides.single().updatedAt)
        assertEquals("2026-10-01T00:00:00Z", response.periodStart)
        assertEquals("2026-11-01T00:00:00Z", response.resetsAt)
    }

    @Test
    fun theDefaultAlertsPortDropsAlerts() = runTest {
        NoBudgetAlerts().publish(BudgetAlert(TestCallers.orgA, null, BudgetScope.ORG, 80, 1, 1, TestCallers.monthStart))
    }
}
