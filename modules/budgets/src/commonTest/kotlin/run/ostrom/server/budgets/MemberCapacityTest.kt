// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.BudgetAction
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetOverrideNotFoundException
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.GetMemberCapacityUseCaseImpl
import run.ostrom.server.budgets.domain.MemberCapacity
import run.ostrom.server.budgets.domain.MemberShare
import run.ostrom.server.budgets.domain.MemberShares
import run.ostrom.server.budgets.domain.WeightedShareBudgetsRepository
import run.ostrom.server.budgets.presentation.MemberCapacityResponse
import run.ostrom.server.budgets.presentation.toResponse
import run.ostrom.server.gateway.domain.AdmissionEntry
import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.gateway.domain.MemberRequests
import run.ostrom.server.gateway.domain.QueueCounts
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.sharing.domain.PoolPolicy
import run.ostrom.server.sharing.domain.PoolShares
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * TeamCode's weighted shares (`readMemberCapacity`): a member's daily limit that stops is their pool's budget times
 * their weight over the pool's weights, lowered to their own cap; and the capacity a member or an admin reads.
 */
class MemberCapacityTest {
    private val org = TestCallers.orgA
    private val member = TestCallers.memberA.memberId

    /** The pools as a test sets them; counts what is read. */
    private class FakePoolShares : PoolShares {
        val policies = mutableMapOf<Uuid, PoolPolicy>()
        val budgets = mutableMapOf<String, Long>()
        val totals = mutableMapOf<String?, Long?>()
        val totalReads = mutableListOf<String?>()

        override suspend fun policyOf(orgId: Uuid, memberId: Uuid) = policies[memberId] ?: PoolPolicy.NONE

        override suspend fun budgetOf(orgId: Uuid, poolId: String): Long? = budgets[poolId]

        override suspend fun totalWeight(orgId: Uuid, poolId: String?, memberId: Uuid): Long? {
            totalReads += poolId
            return totals[poolId]
        }
    }

    private val pools = FakePoolShares()
    private val shares = MemberShares(pools)
    private val typed = InMemoryBudgetsRepository()
    private val limits = WeightedShareBudgetsRepository(typed, shares)

    private fun daily(micros: Long, action: BudgetAction = BudgetAction.BLOCK) =
        BudgetLimit(micros, BudgetWindow.DAY, action)

    private suspend fun orgDaily(micros: Long, action: BudgetAction = BudgetAction.BLOCK) =
        typed.replaceLimits(org, BudgetTarget.Org, listOf(daily(micros, action)), TestCallers.epoch)

    @Test
    fun theHomePoolsBudgetIsTheOrganizationsDailyLimitThatStops() = runTest {
        pools.totals[null] = 11
        pools.policies[member] = PoolPolicy(null, false, 5)
        assertEquals(MemberShare(null, 5, null, null), shares.of(org, member, listOf(BudgetLimit(80_000_000))))
        assertEquals(emptyList(), pools.totalReads, "No budget: the weights are not read")
        assertEquals(MemberShare(null, 5, null, null), shares.of(org, member, listOf(daily(1, BudgetAction.WARN))))
        val share = shares.of(org, member, listOf(daily(80_000_000)))
        assertEquals(MemberShare(null, 5, 11, 80_000_000), share)
        assertEquals(36_363_636, share.micros, "TeamCode's 5/11")
        assertEquals(11, shares.of(org, member, emptyList(), always = true).totalWeight, "Read for the view")
    }

    @Test
    fun aNamedPoolsMembersShareItsBudget() = runTest {
        pools.policies[member] = PoolPolicy("eu", false)
        pools.totals["eu"] = 4
        assertEquals(MemberShare("eu", 1, null, null), shares.of(org, member, listOf(daily(1))), "eu has no budget")
        pools.budgets["eu"] = 40_000_000
        assertEquals(10_000_000, shares.of(org, member, listOf(daily(1))).micros, "The org's limit is not eu's")
    }

    @Test
    fun noShareAppliesWithoutABudgetOrATotal() {
        assertNull(MemberShare(null, 1, 3, null).micros)
        assertNull(MemberShare(null, 1, null, 9).micros)
        assertEquals(MemberShare(null, 1, null, null), MemberShare.UNKNOWN)
    }

    @Test
    fun theShareIsTheMembersDailyLimitThatStopsUnlessTheirOwnIsLess() = runTest {
        typed.replaceLimits(
            org,
            BudgetTarget.Member(member),
            listOf(BudgetLimit(500_000_000), daily(3_000_000, BudgetAction.WARN)),
            TestCallers.epoch,
        )
        assertEquals(typed.limits(org, member), limits.limits(org, member), "No budget: the limits as set")

        orgDaily(80_000_000)
        pools.totals[null] = 8
        val shared = limits.limits(org, member)
        assertEquals(
            listOf(BudgetLimit(500_000_000), daily(3_000_000, BudgetAction.WARN), daily(10_000_000)),
            shared.member,
            "The share added; the monthly limit and the warning kept",
        )
        assertEquals(typed.limits(org, member).org, shared.org)

        typed.replaceLimits(org, BudgetTarget.Member(member), listOf(daily(4_000_000)), TestCallers.epoch)
        assertEquals(listOf(daily(4_000_000)), limits.limits(org, member).member, "Their own cap is less")
        typed.replaceLimits(org, BudgetTarget.Member(member), listOf(daily(40_000_000)), TestCallers.epoch)
        assertEquals(listOf(daily(10_000_000)), limits.limits(org, member).member, "The share is less")

        pools.policies[member] = PoolPolicy(null, false, 0)
        assertEquals(listOf(daily(0)), limits.limits(org, member).member, "Weight 0 stops every call")
    }

    /** The queue as a test sets it. */
    private class FakeQueue(private val requests: MemberRequests) : AdmissionQueueRepository {
        override suspend fun enter(request: AdmissionRequest, first: Boolean) = AdmissionEntry.Admitted

        override suspend fun requeue(orgId: Uuid, id: Uuid) = Unit

        override suspend fun renew(orgId: Uuid, id: Uuid) = Unit

        override suspend fun release(orgId: Uuid, id: Uuid) = Unit

        override suspend fun counts(orgId: Uuid, memberId: Uuid) = QueueCounts(0, 0)

        override suspend fun memberRequests(orgId: Uuid, memberId: Uuid) = requests
    }

    private object Logins : MemberLogins {
        private val ids = mapOf("member" to TestCallers.memberA.memberId, "admin" to TestCallers.adminA.memberId)

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            ids.filterKeys { it in logins }

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
    }

    private val spend = FakeSpendReader()
    private val capacity = GetMemberCapacityUseCaseImpl(
        limits,
        shares,
        spend,
        FakeQueue(MemberRequests(running = 2, waiting = 3)),
        Logins,
        TestClock(),
    )

    @Test
    fun anAdminReadsAMembersCapacityToday() = runTest {
        orgDaily(80_000_000)
        pools.totals[null] = 2
        spend.spent[org to member] = 1_250_000
        val read = capacity(TestCallers.adminA, "member")
        val today = Instant.parse("2026-10-30T00:00:00Z")
        assertEquals(
            MemberCapacity(
                login = "member",
                share = MemberShare(null, 1, 2, 80_000_000),
                dailyLimitMicros = 40_000_000,
                spentTodayMicros = 1_250_000,
                requests = MemberRequests(2, 3),
                resetsAt = Instant.parse("2026-10-31T00:00:00Z"),
            ),
            read,
        )
        assertEquals(listOf(today), spend.sinces, "Spent since 00:00 UTC")
        assertEquals(
            MemberCapacityResponse(
                member = "member",
                weight = 1,
                poolTotalWeight = 2,
                poolDailyMicros = 80_000_000,
                shareMicros = 40_000_000,
                dailyLimitMicros = 40_000_000,
                spentTodayMicros = 1_250_000,
                activeRequests = 2,
                waitingRequests = 3,
                resetsAt = "2026-10-31T00:00:00Z",
            ),
            read.toResponse(),
        )
    }

    @Test
    fun aMemberReadsTheirOwnAndNothingOfAnyoneElse() = runTest {
        assertEquals("member", capacity(TestCallers.memberA, "member").login)
        assertFailsWith<AdminRequiredException> { capacity(TestCallers.memberA, "admin") }
        assertFailsWith<AdminRequiredException>("No login enumeration") { capacity(TestCallers.memberA, "ghost") }
        assertFailsWith<BudgetOverrideNotFoundException> { capacity(TestCallers.adminA, "ghost") }
    }

    @Test
    fun withoutPoolsOrAQueueTheCapacityHasNoShareAndNoCalls() = runTest {
        val bare = GetMemberCapacityUseCaseImpl(typed, null, spend, null, Logins, TestClock())
        val read = bare(TestCallers.memberA, "member")
        assertEquals(MemberShare.UNKNOWN, read.share)
        assertEquals(MemberRequests(0, 0), read.requests)
        assertNull(read.dailyLimitMicros)
        assertNull(read.toResponse().poolTotalWeight)
    }
}
