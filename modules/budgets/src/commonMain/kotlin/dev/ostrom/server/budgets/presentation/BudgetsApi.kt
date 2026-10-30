// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/v1/orgs/{orgId}/budgets`: the org's monthly limits, for its admins. To move to ostrom-core with the API contract.
 *
 * @property orgId The org.
 */
@Serializable
@Resource("/v1/orgs/{orgId}/budgets")
class BudgetsApi(val orgId: String) {
    /**
     * `/members/{memberId}`: one member's own limit.
     *
     * @property parent The budgets.
     * @property memberId The member.
     */
    @Serializable
    @Resource("members/{memberId}")
    class Member(val parent: BudgetsApi, val memberId: String)

    /**
     * `/me`: the caller's own budget, for any member.
     *
     * @property parent The budgets.
     */
    @Serializable
    @Resource("me")
    class Me(val parent: BudgetsApi)
}

/**
 * Replaces the org limit and the default member limit; an absent limit removes it.
 *
 * @property orgLimitMicros What the org may spend per calendar month (UTC), USD millionths.
 * @property defaultMemberLimitMicros What each member may spend per month, unless they have their own limit.
 */
@Serializable
data class PutBudgetsRequest(val orgLimitMicros: Long? = null, val defaultMemberLimitMicros: Long? = null)

/**
 * Sets a member's own limit.
 *
 * @property limitMicros USD millionths per month.
 */
@Serializable
data class PutMemberBudgetRequest(val limitMicros: Long)

/**
 * A member's own limit.
 *
 * @property memberId The member.
 * @property limitMicros The limit.
 * @property updatedAt Its last change (ISO 8601).
 */
@Serializable
data class MemberBudgetResponse(val memberId: String, val limitMicros: Long, val updatedAt: String)

/**
 * An org's budgets.
 *
 * @property orgLimitMicros The org limit, absent when none.
 * @property defaultMemberLimitMicros The default member limit, absent when none.
 * @property overrides Members with their own limit.
 * @property orgSpentMicros What the org spent this month.
 * @property periodStart The start of this month (ISO 8601).
 * @property resetsAt The start of next month (ISO 8601).
 */
@Serializable
data class BudgetsResponse(
    val orgLimitMicros: Long? = null,
    val defaultMemberLimitMicros: Long? = null,
    val overrides: List<MemberBudgetResponse>,
    val orgSpentMicros: Long,
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
