// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/api/v1/members/{login}/budget-overrides`: a member's temporary daily budgets (contract `listBudgetOverrides`,
 * `createBudgetOverride`).
 *
 * @property login The member's login.
 * @property before The id of the last item of the previous page.
 */
@Serializable
@Resource("/api/v1/members/{login}/budget-overrides")
class BudgetOverridesApi(val login: String, val before: String? = null) {
    /**
     * `/{id}/revoke` (contract `revokeBudgetOverride`).
     *
     * @property parent The member's overrides.
     * @property id The override.
     */
    @Serializable
    @Resource("{id}/revoke")
    class Revoke(val parent: BudgetOverridesApi, val id: String)
}

/**
 * A temporary daily budget (contract `BudgetOverride`).
 *
 * @property id Its id.
 * @property member The member's login.
 * @property micros The member's total daily allowance while it lasts, USD millionths.
 * @property createdAt When it was granted (ISO 8601).
 * @property expiresAt When it ends.
 * @property reason Why.
 * @property createdBy The grantor's login.
 * @property replacesId The override it replaced.
 * @property revokedAt When it was revoked.
 * @property revokedBy Who revoked it.
 * @property revocationReason Why.
 */
@Serializable
data class BudgetOverrideDto(
    val id: String,
    val member: String,
    val micros: Long,
    val createdAt: String,
    val expiresAt: String,
    val reason: String,
    val createdBy: String,
    val replacesId: String? = null,
    val revokedAt: String? = null,
    val revokedBy: String? = null,
    val revocationReason: String? = null,
)

/**
 * A page of a member's overrides.
 *
 * @property items Newest first.
 * @property next The `before` of the following page.
 * @property activeId The override in force now.
 */
@Serializable
data class BudgetOverridesPage(
    val items: List<BudgetOverrideDto>,
    val next: String? = null,
    val activeId: String? = null,
)

/**
 * A grant (contract `createBudgetOverride`).
 *
 * @property micros The member's total daily allowance, USD millionths.
 * @property durationMinutes 1 to 1440.
 * @property reason One line.
 * @property replacesId The override in force, to replace it.
 */
@Serializable
data class GrantBudgetOverrideRequest(
    val micros: Long,
    val durationMinutes: Int,
    val reason: String,
    val replacesId: String? = null,
)

/**
 * A revocation.
 *
 * @property reason Why, one line.
 */
@Serializable
data class RevokeBudgetOverrideRequest(val reason: String)
