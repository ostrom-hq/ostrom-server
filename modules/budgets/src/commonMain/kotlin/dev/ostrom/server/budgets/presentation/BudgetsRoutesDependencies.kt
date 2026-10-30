// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.presentation

import dev.ostrom.server.budgets.domain.DeleteMemberBudgetUseCase
import dev.ostrom.server.budgets.domain.GetBudgetsUseCase
import dev.ostrom.server.budgets.domain.GetMyBudgetUseCase
import dev.ostrom.server.budgets.domain.PutBudgetsUseCase
import dev.ostrom.server.budgets.domain.PutMemberBudgetUseCase
import dev.ostrom.server.secrets.domain.CallerResolver

/**
 * What the budgets routes need.
 *
 * @property callers Resolves the member of each call.
 * @property getBudgets Reads an org's budgets.
 * @property putBudgets Replaces the org and default limits.
 * @property putMemberBudget Sets a member's limit.
 * @property deleteMemberBudget Removes it.
 * @property getMyBudget Reads the caller's.
 */
data class BudgetsRoutesDependencies(
    val callers: CallerResolver,
    val getBudgets: GetBudgetsUseCase,
    val putBudgets: PutBudgetsUseCase,
    val putMemberBudget: PutMemberBudgetUseCase,
    val deleteMemberBudget: DeleteMemberBudgetUseCase,
    val getMyBudget: GetMyBudgetUseCase,
)
