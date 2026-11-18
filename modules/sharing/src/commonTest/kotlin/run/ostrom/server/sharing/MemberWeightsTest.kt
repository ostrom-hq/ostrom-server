// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.sharing.domain.InvalidPolicyException
import run.ostrom.server.sharing.domain.MemberPoliciesUseCaseImpl
import run.ostrom.server.sharing.domain.NoMemberPools
import run.ostrom.server.sharing.domain.PoolBudget
import run.ostrom.server.sharing.domain.PoolPolicy
import run.ostrom.server.sharing.domain.PoolShares
import run.ostrom.server.sharing.domain.PoolSharesImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** TeamCode's member weight (`MemberPolicy.weight`): who sets it, its bounds, and the pool weights it sums into. */
class MemberWeightsTest {
    private val pools = InMemoryMemberPools()
    private val budgets = InMemoryPoolBudgets()
    private val logins = object : MemberLogins {
        val byId = mapOf(Sharing.adminA.memberId to "ada", Sharing.memberA.memberId to "mia")

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            byId.entries.filter { it.value in logins }.associate { it.value to it.key }

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            byId.filterKeys { it in memberIds }
    }
    private val policies = MemberPoliciesUseCaseImpl(pools, logins)

    /** The org's members as the orgs module lists them; null is an org past the bound. */
    private var members: List<String>? = emptyList()
    private val limits = mutableListOf<Int>()
    private val directory = object : MemberDirectory {
        override suspend fun isActiveMember(orgId: Uuid, memberId: String) = true

        override suspend fun memberIds(orgId: Uuid, limit: Int): List<String>? = members.also { limits += limit }
    }
    private val shares = PoolSharesImpl(pools, budgets, directory)

    @Test
    fun anAdminSetsAWeightAndAnAbsentOneIsKept() = runTest {
        assertEquals(1, policies.get(Sharing.adminA, "mia").weight, "1 without a policy")
        assertEquals(5, policies.setPolicy(Sharing.adminA, "mia", "eu", null, 5).weight)
        assertEquals(PoolPolicy("eu", false, 5), pools.pools[Sharing.orgA to Sharing.memberA.memberId])
        assertEquals(5, policies.setPolicy(Sharing.adminA, "mia", "us", true).weight, "Absent, the weight is kept")
        assertEquals(5, policies.get(Sharing.memberA, "mia").weight, "A member reads their own")
        policies.setPolicy(Sharing.adminA, "mia", "", null, 0)
        assertEquals(PoolPolicy(null, true, 0), pools.pools[Sharing.orgA to Sharing.memberA.memberId], "0 is kept")
        assertEquals(1000, policies.setPolicy(Sharing.adminA, "mia", "", null, 1000).weight)
    }

    @Test
    fun aWeightOutOfZeroToAThousandIsRefusedAndNothingIsWritten() = runTest {
        for (weight in listOf(-1, 1001, Int.MAX_VALUE)) {
            val refused = assertFailsWith<InvalidPolicyException>("$weight") {
                policies.setPolicy(Sharing.adminA, "mia", "eu", null, weight)
            }
            assertEquals("invalid_policy", refused.type)
            assertEquals("Use a weight from 0 to 1000.", refused.message)
        }
        assertEquals(emptyMap(), pools.pools)
    }

    @Test
    fun noMemberPoolsKeepsTheWeightGiven() = runTest {
        assertEquals(PoolPolicy("eu", false, 7), NoMemberPools.setPolicy(Sharing.orgA, Uuid.random(), "eu", null, 7))
        assertEquals(1, NoMemberPools.setPolicy(Sharing.orgA, Uuid.random(), "eu", null, null).weight)
    }

    @Test
    fun aPoolWeighsItsCurrentMembersAndAMemberWithoutAPolicyWeighsOneInNoPool() = runTest {
        val ada = Uuid.random()
        val (mia, leo, ana) = List(3) { Uuid.random() }
        members = listOf(ada, mia, leo, ana).map { it.toString() } + "not-a-uuid"
        pools.pools[Sharing.orgA to mia] = PoolPolicy("eu", false, 5)
        pools.pools[Sharing.orgA to leo] = PoolPolicy("eu", false, 0)
        pools.pools[Sharing.orgA to ana] = PoolPolicy(null, true, 3)
        // A member who left keeps their policy row: it weighs nothing.
        pools.pools[Sharing.orgA to Uuid.random()] = PoolPolicy("eu", false, 100)

        assertEquals(4, shares.totalWeight(Sharing.orgA, null, ada), "Ada weighs 1 without a policy, Ana 3")
        assertEquals(5, shares.totalWeight(Sharing.orgA, "eu", mia))
        assertEquals(0, shares.totalWeight(Sharing.orgA, "asia", leo))
        val unlisted = Uuid.random()
        pools.pools[Sharing.orgA to unlisted] = PoolPolicy("asia", false, 2)
        assertEquals(2, shares.totalWeight(Sharing.orgA, "asia", unlisted), "The member asking always counts")
        assertEquals(PoolShares.MAX_MEMBERS, limits.first())
        assertEquals(PoolPolicy("eu", false, 5), shares.policyOf(Sharing.orgA, mia))
    }

    @Test
    fun noTotalIsKnownPastTheBoundOrWithoutTheDirectory() = runTest {
        members = null
        assertNull(shares.totalWeight(Sharing.orgA, null, Uuid.random()))
        assertNull(PoolSharesImpl(pools, budgets, null).totalWeight(Sharing.orgA, null, Uuid.random()))
    }

    @Test
    fun aNamedPoolsBudgetSizesItsSharesButAReserveHasNone() = runTest {
        budgets.budgets["eu"] = PoolBudget("eu", 40_000_000, sharedOverflow = false)
        budgets.budgets["reserve"] = PoolBudget("reserve", 10_000_000, sharedOverflow = true)
        assertEquals(40_000_000, shares.budgetOf(Sharing.orgA, "eu"))
        assertNull(shares.budgetOf(Sharing.orgA, "reserve"))
        assertNull(shares.budgetOf(Sharing.orgA, "asia"))
    }

    @Test
    fun aShareIsTheBudgetTimesTheWeightOverTheTotalRoundedDown() {
        assertEquals(36_363_636, PoolShares.share(80_000_000, 5, 11), "TeamCode's 5/11")
        assertEquals(0, PoolShares.share(80_000_000, 0, 11))
        assertEquals(0, PoolShares.share(80_000_000, 1, 0), "A pool that weighs nothing gives nothing")
        assertEquals(1_000_000_000_000_000, PoolShares.share(1_000_000_000_000_000, 1000, 1000), "No overflow")
    }
}
