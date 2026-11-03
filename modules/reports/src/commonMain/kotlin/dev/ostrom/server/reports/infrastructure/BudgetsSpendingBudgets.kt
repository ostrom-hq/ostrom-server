// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.infrastructure

import dev.ostrom.server.budgets.domain.BudgetAction
import dev.ostrom.server.budgets.domain.BudgetPeriod
import dev.ostrom.server.budgets.domain.BudgetWindow
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.reports.domain.ExceededBudget
import dev.ostrom.server.reports.domain.SpendingBudgets
import dev.ostrom.server.reports.domain.Viewer
import kotlin.time.Instant

/**
 * [SpendingBudgets] on the budgets module's limits: the organization's limit, or the member's own (else the default
 * member limit), the very ones the gateway stops on; and the limits the gateway's guard saw reached.
 *
 * @param budgets The limits.
 */
class BudgetsSpendingBudgets(private val budgets: BudgetsRepository) : SpendingBudgets {
    override suspend fun monthlyMicros(viewer: Viewer): Long? = if (viewer.wholeOrganization) {
        budgets.settings(viewer.orgId, 0).orgLimitMicros
    } else {
        budgets.limits(viewer.orgId, viewer.memberId).memberLimitMicros
    }

    override suspend fun exceeded(viewer: Viewer, at: Instant): List<ExceededBudget> = budgets.exceeded(
        orgId = viewer.orgId,
        memberId = viewer.memberId.takeUnless { viewer.wholeOrganization },
        day = BudgetPeriod.day(at).start,
        month = BudgetPeriod.of(at).start,
        max = MAX_EXCEEDED,
    ).map {
        ExceededBudget(
            memberId = it.memberId,
            daily = it.limit.window == BudgetWindow.DAY,
            stops = it.limit.action == BudgetAction.BLOCK,
            limitMicros = it.limit.micros,
            since = it.since,
        )
    }

    private companion object {
        /** More than an organization's budgets ever reach at once: the org's and the members' two windows. */
        const val MAX_EXCEEDED = 200
    }
}
