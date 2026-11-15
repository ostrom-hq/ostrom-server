// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import kotlin.uuid.Uuid

/**
 * The limits with the member's temporary daily budget in force: while it lasts, its amount is the member's own daily
 * limit that stops (it replaces theirs, or the default's, or adds one when they have none); their limits that warn and
 * their monthly ones are kept. Every reader of a call's limits ([BudgetGuardImpl], [LedgerSpendGuard],
 * [GetMyBudgetUseCaseImpl]) reads through it, so the guard and the ledger's admission both see it; the override is read
 * again at each call by the database's clock, never cached, so a grant or a revocation on any replica applies to the
 * next call on every one.
 *
 * @param limits The limits as set.
 * @param overrides The overrides.
 */
class OverriddenBudgetsRepository(
    private val limits: BudgetsRepository,
    private val overrides: BudgetOverridesRepository,
) : BudgetsRepository by limits {
    override suspend fun limits(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetLimits {
        val set = limits.limits(orgId, memberId, workflowId)
        val override = overrides.active(orgId, memberId) ?: return set
        val stopsDaily = { limit: BudgetLimit ->
            limit.window == BudgetWindow.DAY && limit.action == BudgetAction.BLOCK
        }
        return set.copy(
            member = set.member.filterNot(stopsDaily) + BudgetLimit(override.micros, BudgetWindow.DAY),
            override = override,
            baseDailyMicros = set.member.firstOrNull(stopsDaily)?.micros,
        )
    }
}
