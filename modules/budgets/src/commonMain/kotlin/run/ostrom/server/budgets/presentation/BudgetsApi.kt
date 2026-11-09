// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.budgets.presentation

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
 * A subject's spending limits (contract `Budget`): per calendar month and per day (UTC), that stop the calls or only
 * warn. Setting one replaces the subject's limits whole.
 *
 * @property scope `organization` (also `org`), `member` or `workflow`.
 * @property subject For `member`: the member's id (`id` of `/orgs/{org}/members`), or absent for every member without
 *   limits of their own. For `workflow`: the workflow's id, required; the spend is that of the model calls of the
 *   launches that play it.
 * @property usdPerMonth The monthly limit in US dollars; absent, none.
 * @property usdPerDay The daily limit in US dollars; absent, none. Both absent remove the subject's limits.
 * @property hardStop True (the default): a reached limit refuses model calls until its period ends. False: the calls go
 *   on, and the overrun is written to the audit log (`budget.exceeded`) and listed by `GET /spending` (`exceeded`).
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
 * @property items The limits: the org's, the default member's, members' own, then the workflows'.
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
 * @property exhaustedScope `org`, `member` or `workflow` when exhausted.
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
