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

/** Per-account limits; [dailyBudgetMicros] is the contract's `dailyAllocationUsd` in USD millionths. */
@Serializable
data class AccountLimitsDto(
    val maxConcurrent: Int = 8,
    val utilizationCeiling: Double = 0.95,
    val monthlyBudgetMicros: Long? = null,
    val allowedMembers: List<String>? = null,
    val dailyBudgetMicros: Long? = null,
)

/**
 * The contract's `enrollSharedAccount` body, the console's: the token is the value of the secret
 * [credentialSecret] names, read on the server.
 *
 * @property provider `claude` or `codex`, or the vendor's name (`anthropic`, `openai`).
 * @property label The name admins see.
 * @property credentialSecret The secret holding the token: the caller's own, or the org's for an admin.
 * @property sharedWith The members it is lent to, by login, and its groups as `group:<id>`; empty for the whole org.
 * @property dailyAllocationUsd What it may serve per UTC day, in USD, or null.
 */
@Serializable
data class EnrollSharedAccountRequest(
    val provider: String,
    val label: String,
    val credentialSecret: String,
    val sharedWith: List<String> = emptyList(),
    val dailyAllocationUsd: Double? = null,
)

/**
 * Shares an account with its OAuth tokens, read on the member's machine (TeamCode's import, the end-to-end tests):
 * the body `POST /api/v1/accounts` reads when it names no `credentialSecret`.
 */
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
    /** The new daily allocation, in USD (the contract's); `limits.dailyBudgetMicros: null` clears it. */
    val dailyAllocationUsd: Double? = null,
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
 * @property owner The login of the member who shared it (its id if it has none; empty for another member's account).
 * @property sharedWith The logins of the members allowed to use it (ids for members without one); empty for the whole
 *   org.
 * @property paused Whether it is switched off.
 * @property status `ready`, `reconnect_required`, `disabled` or `wrong_account`.
 * @property dailyAllocationUsd What it may serve per UTC day, in USD; absent when unlimited, and for another member's
 *   account.
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
    val dailyAllocationUsd: Double? = null,
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
