// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import kotlin.time.Instant

/** What a limit applies to. */
enum class BudgetScope { ORG, MEMBER, WORKFLOW }

/**
 * Whether a member may spend more. The budgets only decide; the gateway turns [Exhausted] into its answer. Only a
 * limit that stops ([BudgetAction.BLOCK]) decides: a limit that warns never refuses a call. A contract the gateway
 * codes against: names and fields do not change, fields are only added.
 */
sealed interface BudgetDecision {
    /**
     * The call may go on.
     *
     * @property remainingMicros What is left before the nearest limit that stops, USD millionths; null when none
     *   applies.
     */
    data class Allowed(val remainingMicros: Long?) : BudgetDecision

    /**
     * A limit that stops is reached.
     *
     * @property scope The limit reached: the org's comes first when both are, the month's before the day's.
     * @property limitMicros That limit.
     * @property resetsAt The start of the next calendar month, or of the next day for a daily limit (UTC), when
     *   spending may resume.
     * @property window Whether that limit is a day's or a month's.
     */
    data class Exhausted(
        val scope: BudgetScope,
        val limitMicros: Long,
        val resetsAt: Instant,
        val window: BudgetWindow = BudgetWindow.MONTH,
    ) : BudgetDecision
}
