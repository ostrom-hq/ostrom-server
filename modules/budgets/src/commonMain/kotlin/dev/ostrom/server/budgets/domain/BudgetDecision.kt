// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import kotlin.time.Instant

/** What a monthly limit applies to. */
enum class BudgetScope { ORG, MEMBER }

/**
 * Whether a member may spend more this month. The budgets only decide; the gateway turns [Exhausted] into its answer.
 * A contract the gateway codes against: names and fields do not change.
 */
sealed interface BudgetDecision {
    /**
     * The call may go on.
     *
     * @property remainingMicros What is left before the nearest limit, USD millionths; null when no limit applies.
     */
    data class Allowed(val remainingMicros: Long?) : BudgetDecision

    /**
     * A limit is reached.
     *
     * @property scope The limit reached: the org's comes first when both are.
     * @property limitMicros That limit.
     * @property resetsAt The start of the next calendar month (UTC), when spending may resume.
     */
    data class Exhausted(val scope: BudgetScope, val limitMicros: Long, val resetsAt: Instant) : BudgetDecision
}
