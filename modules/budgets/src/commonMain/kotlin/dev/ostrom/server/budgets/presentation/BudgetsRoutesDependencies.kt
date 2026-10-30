// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.presentation

import dev.ostrom.server.budgets.domain.GetBudgetsUseCase
import dev.ostrom.server.budgets.domain.GetMyBudgetUseCase
import dev.ostrom.server.budgets.domain.SetBudgetUseCase
import dev.ostrom.server.secrets.domain.CallerResolver

/**
 * What the budgets routes need.
 *
 * @property callers Resolves the member of each call.
 * @property getBudgets Reads the org's limits.
 * @property setBudget Sets one.
 * @property getMyBudget Reads the caller's own.
 */
data class BudgetsRoutesDependencies(
    val callers: CallerResolver,
    val getBudgets: GetBudgetsUseCase,
    val setBudget: SetBudgetUseCase,
    val getMyBudget: GetMyBudgetUseCase,
)
