// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.infrastructure

import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.reports.domain.SpendingBudgets
import dev.ostrom.server.reports.domain.Viewer

/**
 * [SpendingBudgets] on the budgets module's limits: the organization's limit, or the member's own (else the default
 * member limit), the very ones the gateway stops on.
 *
 * @param budgets The limits.
 */
class BudgetsSpendingBudgets(private val budgets: BudgetsRepository) : SpendingBudgets {
    override suspend fun monthlyMicros(viewer: Viewer): Long? = if (viewer.wholeOrganization) {
        budgets.settings(viewer.orgId, 0).orgLimitMicros
    } else {
        budgets.limits(viewer.orgId, viewer.memberId).memberLimitMicros
    }
}
