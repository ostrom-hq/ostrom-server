// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import kotlin.uuid.Uuid

/**
 * The limits with the member's weighted share of their pool's daily budget (TeamCode's allowance: `budget * weight /
 * totalWeight`, lowered to their own daily cap when it is less). While their pool has a budget, their daily limit that
 * stops is that share, or their own daily limit that stops (their own, or else the default member's) when it is less;
 * their limits that warn and their monthly ones are kept. Weight 0 gives a limit of 0, which stops every call. It
 * reads the organization's limits after the automatic pool size, and a temporary budget override read after it
 * replaces the share ([OverriddenBudgetsRepository]). Nothing is cached: a weight, a pool or a budget changed on any
 * replica counts at the next call on every one.
 *
 * @param limits The limits as set, with the automatic pool size.
 * @param shares The members' shares.
 */
class WeightedShareBudgetsRepository(
    private val limits: BudgetsRepository,
    private val shares: MemberShares,
) : BudgetsRepository by limits {
    override suspend fun limits(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetLimits {
        val set = limits.limits(orgId, memberId, workflowId)
        val share = shares.of(orgId, memberId, set.org).micros ?: return set
        val own = set.member.firstOrNull { it.stopsDaily }?.micros
        val allowance = if (own == null) share else minOf(own, share)
        return set.copy(member = set.member.filterNot { it.stopsDaily } + BudgetLimit(allowance, BudgetWindow.DAY))
    }
}
