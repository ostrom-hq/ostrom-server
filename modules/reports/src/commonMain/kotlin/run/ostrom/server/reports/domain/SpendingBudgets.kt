// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The budgets as a viewer sees them: the monthly one that applies, and those spent past their limit. */
interface SpendingBudgets {
    /**
     * Reads the monthly budget.
     *
     * @param viewer The organization's budget for whoever sees all of it, the member's own otherwise.
     * @return The limit in millionths of a US dollar, or null when there is none.
     */
    suspend fun monthlyMicros(viewer: Viewer): Long?

    /**
     * Reads the budgets spent past their limit in their current day or month.
     *
     * @param viewer All of the organization's for whoever sees all of it, the member's own otherwise.
     * @param at Now.
     * @return The budgets, the first reached first.
     */
    suspend fun exceeded(viewer: Viewer, at: Instant): List<ExceededBudget>
}

/**
 * A budget spent past its limit in its current period.
 *
 * @property memberId The member, for a member's budget; null for the organization's or a workflow's.
 * @property daily Whether its period is the day (else the month).
 * @property stops Whether it stops the calls (else it only warns).
 * @property limitMicros The limit, millionths of a US dollar.
 * @property since When the server saw it reached.
 * @property workflowId The workflow, for a workflow's budget.
 */
data class ExceededBudget(
    val memberId: Uuid?,
    val daily: Boolean,
    val stops: Boolean,
    val limitMicros: Long,
    val since: Instant,
    val workflowId: String? = null,
)
