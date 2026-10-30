// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/api/v1/budgets`: the limits of the caller's org (from the token), as in ostrom-core's `openapi.yaml`
 * (`listBudgets`, `setBudget`); `/me` is an addition.
 */
@Serializable
@Resource("/api/v1/budgets")
class BudgetsApi {
    /**
     * `/me`: the caller's own budget, for any member and for an agent session.
     *
     * @property parent The budgets.
     */
    @Serializable
    @Resource("me")
    class Me(val parent: BudgetsApi = BudgetsApi())
}

/** Scopes of a console token on the budgets routes (a browser session has every user scope). */
object BudgetScopes {
    /** Reads the budgets. */
    const val READ: String = "budgets:read"

    /** Sets them. */
    const val WRITE: String = "budgets:write"
}

/**
 * A spending limit (contract `Budget`): per calendar month (UTC), always a hard stop.
 *
 * @property scope `organization` (also `org`) or `member`.
 * @property subject For `member`: the member's id, or absent for every member without a limit of their own.
 * @property usdPerMonth The limit in US dollars; absent removes it.
 * @property usdPerDay Daily limits are not supported: must be absent.
 * @property hardStop Must be true: a reached limit refuses model calls.
 */
@Serializable
data class BudgetDto(
    val scope: String,
    val subject: String? = null,
    val usdPerMonth: Double? = null,
    val usdPerDay: Double? = null,
    val hardStop: Boolean = true,
)

/**
 * The org's budgets (contract `listBudgets`, with additions).
 *
 * @property items The limits: the org's, the default member's, then members' own.
 * @property next Always absent: every limit fits in one page.
 * @property orgSpentUsd What the org spent this month (addition).
 * @property periodStart The start of this month, ISO 8601 (addition).
 * @property resetsAt The start of next month, ISO 8601 (addition).
 */
@Serializable
data class BudgetsPage(
    val items: List<BudgetDto>,
    val next: String? = null,
    val orgSpentUsd: Double,
    val periodStart: String,
    val resetsAt: String,
)

/**
 * The caller's budget.
 *
 * @property limitMicros Their limit, absent when none applies.
 * @property spentMicros What they spent this month.
 * @property remainingMicros What is left of their limit, absent without a limit.
 * @property resetsAt The start of next month (ISO 8601).
 * @property exhausted Whether model calls are refused now.
 * @property exhaustedScope `org` or `member` when exhausted.
 */
@Serializable
data class MyBudgetResponse(
    val limitMicros: Long? = null,
    val spentMicros: Long,
    val remainingMicros: Long? = null,
    val resetsAt: String,
    val exhausted: Boolean,
    val exhaustedScope: String? = null,
)
