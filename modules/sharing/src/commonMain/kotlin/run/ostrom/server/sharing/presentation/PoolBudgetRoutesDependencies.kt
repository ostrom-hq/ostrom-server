// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.sharing.domain.PoolBudgetsUseCase

/**
 * What the pool budget routes need.
 *
 * @property callers Resolves the member a credential acts for.
 * @property budgets The pools' budgets.
 * @property audit Writes a change of a budget to the organization's audit log.
 */
data class PoolBudgetRoutesDependencies(
    val callers: CallerResolver,
    val budgets: PoolBudgetsUseCase,
    val audit: OrgAuditTrail,
)
