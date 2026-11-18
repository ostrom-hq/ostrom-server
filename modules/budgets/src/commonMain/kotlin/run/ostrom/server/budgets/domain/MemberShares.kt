// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.sharing.domain.PoolShares
import kotlin.uuid.Uuid

/**
 * A member's weighted share of their pool's daily budget (TeamCode's `MemberCapacity`: `weight`, `poolTotalWeight`,
 * `poolDailyUnits` and the share they give).
 *
 * @property poolId Their home pool, or null for none.
 * @property weight Their weight.
 * @property totalWeight The weights of their pool's members, theirs included; null when unknown.
 * @property poolDailyMicros Their pool's daily budget: the organization's daily limit that stops for a member in no
 *   pool, the named pool's budget otherwise; null when their pool has none.
 */
data class MemberShare(val poolId: String?, val weight: Int, val totalWeight: Long?, val poolDailyMicros: Long?) {
    /** Their share of the budget, USD millionths a day; null when no share applies (no budget, or no total known). */
    val micros: Long?
        get() = if (poolDailyMicros == null || totalWeight == null) {
            null
        } else {
            PoolShares.share(poolDailyMicros, weight, totalWeight)
        }

    /** A member's share where the pools are not known (no sharing module): weight 1, in no pool, nothing applies. */
    companion object {
        /** The share of a server without pools. */
        val UNKNOWN: MemberShare = MemberShare(null, 1, null, null)
    }
}

/**
 * Reads [MemberShare]s on the sharing module's [PoolShares]: the member's policy, then their pool's budget, then, only
 * when there is a budget (or when [of] is asked for the view), the weights of their pool.
 *
 * @param shares The pools' policies, budgets and weights.
 */
class MemberShares(private val shares: PoolShares) {
    /**
     * The share of [memberId] in [orgId].
     *
     * @param org The organization's limits as they apply now (the automatic pool size included): its daily limit that
     *   stops is the budget of the members in no pool.
     * @param always Whether to sum the pool's weights even when it has no budget (to show them).
     */
    suspend fun of(orgId: Uuid, memberId: Uuid, org: List<BudgetLimit>, always: Boolean = false): MemberShare {
        val policy = shares.policyOf(orgId, memberId)
        val pool = policy.poolId
        val budget = if (pool == null) org.firstOrNull { it.stopsDaily }?.micros else shares.budgetOf(orgId, pool)
        val total = if (budget != null || always) shares.totalWeight(orgId, pool) else null
        return MemberShare(pool, policy.weight, total, budget)
    }
}

/** Whether this limit stops the calls of a day. */
internal val BudgetLimit.stopsDaily: Boolean
    get() = window == BudgetWindow.DAY && action == BudgetAction.BLOCK
