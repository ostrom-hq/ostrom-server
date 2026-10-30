package dev.ostrom.server.sharing.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/v1/orgs/{orgId}/accounts`: the org's shared personal accounts. To move to ostrom-core with the API contract. */
@Serializable
@Resource("/v1/orgs/{orgId}/accounts")
class AccountsApi(val orgId: String) {
    /** `/accounts/{id}`: one account. */
    @Serializable
    @Resource("{id}")
    class Id(val parent: AccountsApi, val id: String)
}

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
    val disabled: Boolean? = null,
    val limits: AccountLimitsDto? = null,
)

/** A provider window. */
@Serializable
data class QuotaWindowDto(val window: String, val utilization: Double, val resetsAt: String? = null, val observedAt: String)

/** An account, tokens never included; identity fields only for admins and its owner. */
@Serializable
data class SharedAccountResponse(
    val id: String,
    val provider: String,
    val label: String,
    val email: String? = null,
    val providerAccountId: String? = null,
    val ownerMemberId: String? = null,
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
data class SharedAccountsResponse(val accounts: List<SharedAccountResponse>)
