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
 * A change of a member's policy (TeamCode's `PolicyRequest`, its pool only).
 *
 * @property poolId The member's home pool: 1 to 36 letters, digits, underscores or hyphens; an empty string takes
 *   them out of their pool.
 */
@Serializable
data class UpdateMemberPolicyRequest(val poolId: String)

/**
 * A member's policy.
 *
 * @property member The member's login.
 * @property poolId Their home pool, absent for none: only the shared accounts of that pool serve them, and a member in
 *   no pool is served by the accounts in none.
 */
@Serializable
data class MemberPolicyResponse(val member: String, val poolId: String? = null)
