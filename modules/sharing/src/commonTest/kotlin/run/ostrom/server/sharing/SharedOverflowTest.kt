// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.sharing.PoolSpend
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.CivilCalendar
import run.ostrom.server.sharing.domain.InvalidOverflowPoolException
import run.ostrom.server.sharing.domain.InvalidPoolBudgetException
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.ListSharedAccountsUseCaseImpl
import run.ostrom.server.sharing.domain.MemberNames
import run.ostrom.server.sharing.domain.MemberPoolScopeImpl
import run.ostrom.server.sharing.domain.MemberPoliciesUseCaseImpl
import run.ostrom.server.sharing.domain.MemberPolicy
import run.ostrom.server.sharing.domain.NoAccountGroups
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.NoMemberLogins
import run.ostrom.server.sharing.domain.NoOverflowPools
import run.ostrom.server.sharing.domain.NoPoolScope
import run.ostrom.server.sharing.domain.NoQuotaHistory
import run.ostrom.server.sharing.domain.OverflowPoolLimitException
import run.ostrom.server.sharing.domain.OverflowPoolsImpl
import run.ostrom.server.sharing.domain.OverflowPrimaryPoolException
import run.ostrom.server.sharing.domain.OverflowReserve
import run.ostrom.server.sharing.domain.PoolBudget
import run.ostrom.server.sharing.domain.PoolBudgetRepository
import run.ostrom.server.sharing.domain.PoolBudgetSave
import run.ostrom.server.sharing.domain.PoolBudgets
import run.ostrom.server.sharing.domain.PoolBudgetsUseCaseImpl
import run.ostrom.server.sharing.domain.PoolPolicy
import run.ostrom.server.sharing.domain.PoolScope
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.infrastructure.ProviderQuotaHeaderParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The pools' budgets in memory: [answer] is what a save of a shared pool answers. */
class InMemoryPoolBudgets : PoolBudgetRepository {
    val budgets = mutableMapOf<String, PoolBudget>()
    var answer = PoolBudgetSave.SAVED
    var reads = 0

    override suspend fun list(orgId: Uuid): List<PoolBudget> = budgets.values.sortedBy { it.poolId }

    override suspend fun save(orgId: Uuid, budget: PoolBudget): PoolBudgetSave {
        if (budget.sharedOverflow && answer != PoolBudgetSave.SAVED) return answer
        budgets[budget.poolId] = budget
        return PoolBudgetSave.SAVED
    }

    override suspend fun reserves(orgId: Uuid): List<OverflowReserve> {
        reads++
        return budgets.values.filter { it.sharedOverflow }
            .map { OverflowReserve(it.poolId, it.dailyMicros) }
    }
}

/** What was served from each pool, as a test sets it, and the reads made. */
class FakePoolSpend : PoolSpend {
    val spent = mutableMapOf<String, Long>()
    val reads = mutableListOf<Pair<Set<String>, Instant>>()

    override suspend fun spentMicros(orgId: Uuid, poolIds: Collection<String>, since: Instant): Map<String, Long> {
        reads += poolIds.toSet() to since
        return spent.filterKeys { it in poolIds }
    }
}

/** TeamCode's shared overflow pools: their budgets, the members allowed them, and how a lease borrows them. */
class SharedOverflowTest {
    private val repository = InMemoryAccounts()
    private val clock = TestClock(Sharing.epoch + 5.hours)
    private val cipher = AccountTokenCipher(ContextCipher())
    private val pools = InMemoryMemberPools()
    private val budgets = InMemoryPoolBudgets()
    private val spend = FakePoolSpend()
    private val overflow = OverflowPoolsImpl(budgets, spend, clock)
    private val useCase = PoolBudgetsUseCaseImpl(budgets)
    private val logins = object : MemberLogins {
        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            mapOf("mia" to Sharing.memberA.memberId).filterKeys { it in logins }

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
    }
    private val policies = MemberPoliciesUseCaseImpl(pools, logins)
    private val pool = SharedAccountPool(
        repository,
        repository,
        tokens(repository, cipher, FakeRefresher(RefreshResult.Revoked), clock),
        InMemorySlots(clock, repository),
        ProviderQuotaHeaderParser(),
        NoAccountSpendReader(),
        clock,
        NoAccountGroups,
        JsonLogger(LogLevel.ERROR, sink = {}),
        NoQuotaHistory,
        pools,
        overflow,
    )

    /** An account of [poolId], cooling down for an hour when [cooling]. */
    private suspend fun account(label: String, poolId: String?, priority: Int = 0, cooling: Boolean = false) =
        SharedAccount(
            Uuid.random(), Sharing.orgA, AccountProvider.CLAUDE, label, null, "acct-$label", null, priority,
            AccountStatus.READY, AccountLimits(), if (cooling) clock.now() + 1.hours else null, clock.now(),
            poolId = poolId,
        ).also { account ->
            repository.accounts += account
            repository.envelopes[account.id] = cipher.seal(
                AccountCredentials("access-$label", "refresh-$label", clock.now() + 1.days),
                Sharing.orgA,
                account.id,
            )
        }

    private fun allow(poolId: String?, allowed: Boolean = true) {
        pools.pools[Sharing.orgA to Sharing.memberA.memberId] = PoolPolicy(poolId, allowed)
    }

    private fun reserve(poolId: String, dailyMicros: Long) {
        budgets.budgets[poolId] = PoolBudget(poolId, dailyMicros, true)
    }

    private suspend fun leased(estimate: Long = 0): String? {
        val result = pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null) { estimate }
        return when (result) {
            is LeaseResult.Leased -> result.lease.account.label.also { result.lease.release() }
            is LeaseResult.Refused -> null
        }
    }

    @Test
    fun aMemberAllowedOverflowBorrowsAReserveOnceTheirHomeIsExhaustedWhileItsBudgetLasts() = runTest {
        account("home", "eu", cooling = true)
        account("reserve", "reserve")
        reserve("reserve", 1_000)
        allow("eu")
        assertEquals("reserve", leased(400), "The home pool cools down: the reserve serves")
        spend.spent["reserve"] = 600
        assertEquals("reserve", leased(400), "1,000 - 600 still covers 400")
        assertEquals(null, leased(401), "It no longer covers 401")
        spend.spent["reserve"] = 1_000
        assertEquals(null, leased(), "Spent: closed even to a call priced at nothing")
        assertEquals(CivilCalendar.dayStart(clock.now()), spend.reads.last().second, "Today's spend, from 00:00 UTC")
        assertEquals(setOf("reserve"), spend.reads.last().first, "By the pool the calls were served from")
    }

    @Test
    fun homeAccountsComeFirstEvenBelowAReserveOfHigherPriority() = runTest {
        account("reserve", "reserve", priority = 100)
        account("home", "eu")
        reserve("reserve", 1_000_000)
        allow("eu")
        repeat(3) { assertEquals("home", leased(), "Home first") }
        allow(null)
        account("none", null)
        assertEquals("none", leased(), "A member in no pool borrows too, the accounts in none first")
    }

    @Test
    fun aMemberNotAllowedNeverBorrowsAndTheReservesAreNotRead() = runTest {
        account("home", "eu", cooling = true)
        account("reserve", "reserve")
        reserve("reserve", 1_000_000)
        allow("eu", allowed = false)
        assertEquals(null, leased())
        assertEquals(0, budgets.reads)
        assertEquals(emptyList(), spend.reads)
    }

    @Test
    fun noReserveAsksNeitherTheEstimateNorTheSpend() = runTest {
        account("home", "eu")
        allow("eu")
        var asked = 0
        val result = pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null) {
            asked++
            0
        }
        assertIs<LeaseResult.Leased>(result).lease.release()
        assertEquals(0, asked)
        assertEquals(emptyList(), spend.reads)
        assertEquals(emptySet(), NoOverflowPools.funded(Sharing.orgA) { 1 })
    }

    @Test
    fun aLeaseWithoutAnEstimateCountsTheLeastACallCosts() = runTest {
        account("reserve", "reserve")
        reserve("reserve", 1)
        allow("eu")
        val result = pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null)
        assertEquals("reserve", assertIs<LeaseResult.Leased>(result).lease.account.label)
        result.lease.release()
    }

    @Test
    fun aMemberListsTheirPoolsTheReservesWhenAllowedAndTheirOwnAccounts() = runTest {
        val home = account("home", "eu")
        val borrowed = account("reserve", "reserve")
        val other = account("other", "us")
        val own = account("own", "us").copy(ownerMemberId = Sharing.memberA.memberId)
        repository.accounts.replaceAll { if (it.id == own.id) own else it }
        reserve("reserve", 1)
        val list = ListSharedAccountsUseCaseImpl(
            repository, repository, pool, NoAccountSpendReader(), clock, MemberNames(NoMemberLogins),
            MemberPoolScopeImpl(pools, budgets),
        )
        suspend fun listed(caller: Caller) = list(caller).map { it.account.id }.toSet()

        allow("eu")
        assertEquals(setOf(home.id, borrowed.id, own.id), listed(Sharing.memberA), "Home, reserve, their own")
        val labels = list(Sharing.memberA).associate { it.account.id to it.displayLabel }
        assertEquals("own", labels[own.id], "Their own account named")
        val numbered = setOf(labels[home.id], labels[borrowed.id])
        assertTrue(numbered.all { it in setOf("Account 1", "Account 2", "Account 3") }, "Numbered among what they see")
        allow("eu", allowed = false)
        val reads = budgets.reads
        assertEquals(setOf(home.id, own.id), listed(Sharing.memberA), "Not allowed: no reserve")
        assertEquals(reads, budgets.reads, "Nor read")
        assertEquals(setOf(home.id, borrowed.id, other.id, own.id), listed(Sharing.adminA), "An admin: every one")
        assertEquals(PoolScope(null, emptySet()), NoPoolScope.of(Sharing.orgA, Sharing.memberA.memberId))
    }

    @Test
    fun anAdminSavesAndListsPoolBudgets() = runTest {
        val saved = useCase.save(Sharing.adminA, PoolBudget("reserve", PoolBudgets.MAX_DAILY_MICROS, true))
        assertEquals(PoolBudget("reserve", 100_000_000_000, true), saved)
        useCase.save(Sharing.adminA, PoolBudget("eu", 1, false))
        assertEquals(listOf("eu", "reserve"), useCase.list(Sharing.adminA).map { it.poolId })
        assertFailsWith<AdminRequiredException> { useCase.list(Sharing.memberA) }
        assertFailsWith<AdminRequiredException> { useCase.save(Sharing.memberA, PoolBudget("eu", 1, false)) }
    }

    @Test
    fun aBudgetOutOfItsFormOrTheHomePoolIsRefused() = runTest {
        for (budget in listOf(
            PoolBudget("eu", 0, false),
            PoolBudget("eu", PoolBudgets.MAX_DAILY_MICROS + 1, false),
            PoolBudget("eu west", 1, false),
            PoolBudget("a".repeat(37), 1, true),
            PoolBudget("", 1, false),
        )) {
            val refused = assertFailsWith<InvalidPoolBudgetException>(budget.toString()) {
                useCase.save(Sharing.adminA, budget)
            }
            assertEquals("invalid_pool_budget", refused.type)
        }
        val home = assertFailsWith<InvalidOverflowPoolException> {
            useCase.save(Sharing.adminA, PoolBudget("", 1, true))
        }
        assertEquals("invalid_overflow_pool", home.type)
        assertEquals(emptyMap(), budgets.budgets)
    }

    @Test
    fun aSharedPoolWithPrimaryMembersOrPastTheLimitConflicts() = runTest {
        budgets.answer = PoolBudgetSave.PRIMARY_POOL
        val primary = assertFailsWith<OverflowPrimaryPoolException> {
            useCase.save(Sharing.adminA, PoolBudget("eu", 1, true))
        }
        assertEquals("overflow_primary_pool", primary.type)
        budgets.answer = PoolBudgetSave.LIMIT
        val limit = assertFailsWith<OverflowPoolLimitException> {
            useCase.save(Sharing.adminA, PoolBudget("eu", 1, true))
        }
        assertEquals("overflow_pool_limit", limit.type)
        assertEquals("At most 16 shared overflow pools are allowed.", limit.message)
        assertEquals(PoolBudget("eu", 1, false), useCase.save(Sharing.adminA, PoolBudget("eu", 1, false)))
    }

    @Test
    fun aMemberIsAllowedOverflowButNeverPutInASharedPool() = runTest {
        val allowed = policies.setPolicy(Sharing.adminA, "mia", "eu", true)
        assertEquals(MemberPolicy("mia", Sharing.memberA.memberId, "eu", true), allowed)
        assertEquals(allowed, policies.setPolicy(Sharing.adminA, "mia", "eu", null), "Absent, the overflow is kept")
        assertEquals(true, policies.setPolicy(Sharing.adminA, "mia", "", null).allowSharedOverflow, "In no pool too")
        pools.reserves += "reserve"
        val refused = assertFailsWith<OverflowPrimaryPoolException> {
            policies.setPolicy(Sharing.adminA, "mia", "reserve", null)
        }
        assertEquals("overflow_primary_pool", refused.type)
        assertEquals(PoolPolicy(null, true), pools.pools[Sharing.orgA to Sharing.memberA.memberId])
        assertEquals(PoolPolicy(null, false), policies.setPolicy(Sharing.adminA, "mia", "", false).let {
            PoolPolicy(it.poolId, it.allowSharedOverflow)
        })
        assertEquals(emptyMap(), pools.pools, "No pool and no overflow: no policy kept")
    }
}
