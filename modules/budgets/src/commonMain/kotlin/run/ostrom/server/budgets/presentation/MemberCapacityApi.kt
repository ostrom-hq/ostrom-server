// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/api/v1/members/{login}/capacity`: a member's capacity today (TeamCode's `/api/v1/members/{id}/capacity`).
 *
 * @property login The member's login.
 */
@Serializable
@Resource("/api/v1/members/{login}/capacity")
class MemberCapacityApi(val login: String)

/**
 * A member's capacity today (TeamCode's `CapacityView`, in USD millionths rather than credits).
 *
 * @property member The member's login.
 * @property poolId Their home pool, absent for none.
 * @property weight Their weight in their pool's split of its daily budget.
 * @property poolTotalWeight The weights of their pool's members, theirs included; absent when unknown.
 * @property poolDailyMicros Their pool's daily budget: the organization's daily limit that stops for a member in no
 *   pool, the named pool's budget otherwise; absent when their pool has none.
 * @property shareMicros Their share of it (`poolDailyMicros * weight / poolTotalWeight`); absent when none applies.
 * @property dailyLimitMicros Their daily limit that stops now: the share, lowered to their own daily limit (or every
 *   member's) when it is less, or a temporary budget override; absent when none applies.
 * @property spentTodayMicros What their settled calls cost since 00:00 UTC.
 * @property activeRequests Their calls running now.
 * @property waitingRequests Their calls waiting in the admission queue now.
 * @property resetsAt The next 00:00 UTC (ISO 8601).
 */
@Serializable
data class MemberCapacityResponse(
    val member: String,
    val poolId: String? = null,
    val weight: Int,
    val poolTotalWeight: Long? = null,
    val poolDailyMicros: Long? = null,
    val shareMicros: Long? = null,
    val dailyLimitMicros: Long? = null,
    val spentTodayMicros: Long,
    val activeRequests: Int,
    val waitingRequests: Int,
    val resetsAt: String,
)
