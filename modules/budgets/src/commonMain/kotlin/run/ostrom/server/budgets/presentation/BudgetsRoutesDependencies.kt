// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import run.ostrom.server.budgets.domain.GetBudgetsUseCase
import run.ostrom.server.budgets.domain.GetMyBudgetUseCase
import run.ostrom.server.budgets.domain.GrantBudgetOverrideUseCase
import run.ostrom.server.budgets.domain.ListBudgetOverridesUseCase
import run.ostrom.server.budgets.domain.RevokeBudgetOverrideUseCase
import run.ostrom.server.budgets.domain.SetBudgetUseCase
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.secrets.domain.CallerResolver

/**
 * What the budgets routes need.
 *
 * @property callers Resolves the member of each call.
 * @property getBudgets Reads the org's limits.
 * @property setBudget Sets one.
 * @property getMyBudget Reads the caller's own.
 * @property audit Writes a change of a limit to the organization's audit log.
 * @property listOverrides Reads a member's temporary daily budgets.
 * @property grantOverride Grants one.
 * @property revokeOverride Revokes one.
 */
data class BudgetsRoutesDependencies(
    val callers: CallerResolver,
    val getBudgets: GetBudgetsUseCase,
    val setBudget: SetBudgetUseCase,
    val getMyBudget: GetMyBudgetUseCase,
    val audit: OrgAuditTrail,
    val listOverrides: ListBudgetOverridesUseCase,
    val grantOverride: GrantBudgetOverrideUseCase,
    val revokeOverride: RevokeBudgetOverrideUseCase,
)
