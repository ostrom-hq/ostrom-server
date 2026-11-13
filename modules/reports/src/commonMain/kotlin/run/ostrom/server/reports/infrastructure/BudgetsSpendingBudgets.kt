// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.infrastructure

import run.ostrom.server.budgets.domain.BudgetAction
import run.ostrom.server.budgets.domain.BudgetPeriod
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.BudgetsRepository
import run.ostrom.server.reports.domain.ExceededBudget
import run.ostrom.server.reports.domain.SpendingBudgets
import run.ostrom.server.reports.domain.Viewer
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
            workflowId = it.workflowId,
        )
    }

    private companion object {
        /** More than an organization's budgets ever reach at once: the org's, the members' and the workflows'. */
        const val MAX_EXCEEDED = 200
    }
}
