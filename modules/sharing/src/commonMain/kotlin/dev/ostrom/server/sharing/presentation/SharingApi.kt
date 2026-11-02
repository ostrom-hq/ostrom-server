// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `/api/v1/accounts`: the shared subscription accounts of the caller's org (from the token), as in ostrom-core's
 * `openapi.yaml` (`listSharedAccounts`, `enrollSharedAccount`).
 */
@Serializable
@Resource("/api/v1/accounts")
class AccountsApi {
    /**
     * `/api/v1/accounts/{id}`: one account (`updateSharedAccount`, `removeSharedAccount`).
     *
     * @property id The account.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: AccountsApi = AccountsApi(), val id: String)
}

/** `/api/v1/capacity`: what the shared accounts have left (`getCapacity`). */
@Serializable
@Resource("/api/v1/capacity")
class CapacityApi

/** Per-account limits. */
@Serializable
data class AccountLimitsDto(
    val maxConcurrent: Int = 8,
    val utilizationCeiling: Double = 0.95,
    val monthlyBudgetMicros: Long? = null,
    val allowedMembers: List<String>? = null,
)

/** Shares an account: tokens read by `ostrom accounts share` on the member's machine. */
@Serializable
data class ShareAccountRequest(
    val provider: String,
    val label: String,
    val email: String? = null,
    val providerAccountId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val limits: AccountLimitsDto = AccountLimitsDto(),
) {
    override fun toString(): String = "ShareAccountRequest(provider=$provider, label=$label, tokens=[REDACTED])"
}

/** Changes an account. */
@Serializable
data class UpdateAccountRequest(
    val label: String? = null,
    val priority: Int? = null,
    val paused: Boolean? = null,
    val sharedWith: List<String>? = null,
    /** The limits to change: a key present replaces the current value (`null` clears a budget), an absent one stays. */
    val limits: JsonObject? = null,
)

/** A provider window. */
@Serializable
data class QuotaWindowDto(
    val window: String,
    val utilization: Double,
    val resetsAt: String? = null,
    val observedAt: String,
)

/**
 * An account (contract `SharedAccount`, with additions), tokens never included; identity fields only for admins and
 * its owner.
 *
 * @property owner The member who shared it (empty for another member's account).
 * @property sharedWith The members allowed to use it; empty for the whole org.
 * @property paused Whether it is switched off.
 * @property status `ready`, `reconnect_required`, `disabled` or `wrong_account`.
 */
@Serializable
data class SharedAccountResponse(
    val id: String,
    val provider: String,
    val label: String,
    val email: String? = null,
    val providerAccountId: String? = null,
    val owner: String = "",
    val sharedWith: List<String> = emptyList(),
    val paused: Boolean = false,
    val mine: Boolean,
    val priority: Int,
    val status: String,
    val limits: AccountLimitsDto,
    val cooldownUntil: String? = null,
    val windows: List<QuotaWindowDto> = emptyList(),
    val inFlight: Int = 0,
    val spentMicros: Long = 0,
    val createdAt: String,
)

/** The org's accounts. */
@Serializable
data class SharedAccountsResponse(val items: List<SharedAccountResponse>, val next: String? = null)

/**
 * The pool of one provider.
 *
 * @property provider `claude` or `codex`.
 * @property accounts Accounts of the pool.
 * @property ready Accounts that can serve now.
 * @property inFlight Requests in flight.
 */
@Serializable
data class CapacityPoolDto(val provider: String, val accounts: Int, val ready: Int, val inFlight: Int)

/**
 * What the shared accounts have left (contract `Capacity`).
 *
 * @property accounts Each account, as the caller may see it.
 * @property pools Totals by provider.
 * @property computedAt When.
 */
@Serializable
data class CapacityResponse(
    val accounts: List<SharedAccountResponse>,
    val pools: List<CapacityPoolDto>,
    val computedAt: String,
)
