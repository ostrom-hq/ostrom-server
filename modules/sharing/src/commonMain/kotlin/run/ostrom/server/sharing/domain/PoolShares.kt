// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import kotlin.uuid.Uuid

/**
 * The weighted split of a pool's daily budget among its members (TeamCode's `readMemberCapacity`): a member's share is
 * the budget times their weight over the weights of the pool's members, every current member of the organization
 * counted in the pool of their policy (none, weight 1, without one). The budget of the members in no pool is the
 * organization's daily limit, which the budgets module holds; a named pool's is its [PoolBudget]. Nothing is cached:
 * a weight or a pool changed on any replica counts at the next read on every one.
 */
interface PoolShares {
    /** The pool and weight of [memberId] in [orgId], [PoolPolicy.NONE] without a policy. */
    suspend fun policyOf(orgId: Uuid, memberId: Uuid): PoolPolicy

    /**
     * The daily budget of the named pool [poolId] in [orgId], USD millionths; null without one, or for a shared
     * overflow reserve, which no member calls home.
     */
    suspend fun budgetOf(orgId: Uuid, poolId: String): Long?

    /**
     * The weights of the members of [poolId] (null: the members in no pool) in [orgId], summed; null when the
     * organization's members cannot be listed (more than [MAX_MEMBERS], or no directory of them).
     */
    suspend fun totalWeight(orgId: Uuid, poolId: String?): Long?

    /** The bounds and the split. */
    companion object {
        /** The most members of an organization whose weights are summed. */
        const val MAX_MEMBERS: Int = 5000

        /**
         * A member's share of [budgetMicros] (TeamCode's `budget * weight / totalWeight`, rounded down): nothing when
         * the pool weighs nothing.
         */
        fun share(budgetMicros: Long, weight: Int, totalWeight: Long): Long =
            if (totalWeight == 0L) 0 else budgetMicros * weight / totalWeight
    }
}

/**
 * [PoolShares] on the members' policies, the pools' budgets and the organization's members.
 *
 * @param pools The members' policies.
 * @param budgets The named pools' budgets.
 * @param members The orgs module's directory, or null when it is not loaded (no total is then known).
 */
class PoolSharesImpl(
    private val pools: MemberPoolRepository,
    private val budgets: PoolBudgetRepository,
    private val members: MemberDirectory?,
) : PoolShares {
    override suspend fun policyOf(orgId: Uuid, memberId: Uuid): PoolPolicy = pools.policyOf(orgId, memberId)

    override suspend fun budgetOf(orgId: Uuid, poolId: String): Long? =
        budgets.list(orgId).firstOrNull { it.poolId == poolId && !it.sharedOverflow }?.dailyMicros

    override suspend fun totalWeight(orgId: Uuid, poolId: String?): Long? {
        val ids = members?.memberIds(orgId, PoolShares.MAX_MEMBERS)?.mapNotNull(Uuid::parseOrNull) ?: return null
        val policies = pools.policiesOf(orgId, ids)
        return ids.sumOf { id ->
            val policy = policies[id] ?: PoolPolicy.NONE
            if (policy.poolId == poolId) policy.weight.toLong() else 0L
        }
    }
}
