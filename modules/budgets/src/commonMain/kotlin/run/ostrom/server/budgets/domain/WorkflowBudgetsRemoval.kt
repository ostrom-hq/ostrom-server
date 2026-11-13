// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.kernel.workflows.WorkflowRemovals
import kotlin.uuid.Uuid

/**
 * A deleted workflow's budgets go with it: its limits and alerts are forgotten and the org's cached decisions with
 * them, so that the budgets list never shows a workflow that is gone and a workflow created again under the same id
 * starts without a budget.
 *
 * @param repository The limits.
 * @param cache The decisions to drop.
 */
class WorkflowBudgetsRemoval(
    private val repository: BudgetsRepository,
    private val cache: BudgetDecisionCache,
) : WorkflowRemovals {
    override suspend fun removed(orgId: Uuid, workflowId: String) {
        repository.removeWorkflow(orgId, workflowId)
        cache.invalidate(orgId)
    }
}
