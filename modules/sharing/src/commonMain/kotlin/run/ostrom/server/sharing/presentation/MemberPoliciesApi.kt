// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/api/v1/members/{login}/policy`: a member's routing policy, their home pool (TeamCode's
 * `/api/v1/members/{id}/policy`).
 *
 * @property login The member's login.
 */
@Serializable
@Resource("/api/v1/members/{login}/policy")
class MemberPolicyApi(val login: String)

/**
 * A change of a member's policy (TeamCode's `PolicyRequest`, its pools and weight).
 *
 * @property poolId The member's home pool: 1 to 36 letters, digits, underscores or hyphens; an empty string takes
 *   them out of their pool.
 * @property allowSharedOverflow Whether they may borrow the shared overflow pools; absent keeps what it was.
 * @property weight Their weight in their pool's split of its daily budget, 0 to 1000; absent keeps what it was.
 */
@Serializable
data class UpdateMemberPolicyRequest(
    val poolId: String,
    val allowSharedOverflow: Boolean? = null,
    val weight: Int? = null,
)

/**
 * A member's policy.
 *
 * @property member The member's login.
 * @property poolId Their home pool, absent for none: only the shared accounts of that pool serve them, and a member in
 *   no pool is served by the accounts in none.
 * @property allowSharedOverflow Whether they may borrow the accounts of the shared overflow pools while their budget
 *   lasts.
 * @property weight Their weight in their pool's split of its daily budget (1 by default, 0 gives them nothing).
 */
@Serializable
data class MemberPolicyResponse(
    val member: String,
    val poolId: String? = null,
    val allowSharedOverflow: Boolean,
    val weight: Int,
)
