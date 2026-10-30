package dev.ostrom.server.sharing.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The subscription a shared account belongs to.
 *
 * @property wire The name in the API and the usage ledger.
 */
enum class AccountProvider(val wire: String) {
    /** A Claude Pro or Max subscription (Claude Code OAuth), serving the Anthropic Messages API. */
    CLAUDE("claude"),

    /** A ChatGPT subscription (Codex OAuth), serving the OpenAI Responses API. */
    CODEX("codex"),
}

/** Whether an account serves. */
enum class AccountStatus {
    /** Serves. */
    READY,

    /** Its refresh token was refused: the owner signs in again. */
    RECONNECT_REQUIRED,

    /** Turned off by an admin or its owner. */
    DISABLED,
}

/**
 * Per-account limits, on top of the provider's own windows.
 *
 * @property maxConcurrent Requests in flight at once on the account.
 * @property utilizationCeiling The share of a provider window (0..1) at which the account stops taking requests,
 *   leaving its owner room.
 * @property monthlyBudgetMicros Equivalent API cost (USD millionths) the account may serve per calendar month, or null.
 * @property allowedMembers The members who may use it, or null for the whole org.
 */
data class AccountLimits(
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    val utilizationCeiling: Double = DEFAULT_CEILING,
    val monthlyBudgetMicros: Long? = null,
    val allowedMembers: Set<Uuid>? = null,
) {
    companion object {
        /** Default concurrency, as TeamCode. */
        const val DEFAULT_MAX_CONCURRENT: Int = 8

        /** Default ceiling: 95 % of any window. */
        const val DEFAULT_CEILING: Double = 0.95
    }
}

/**
 * A personal subscription account pooled in an org.
 *
 * @property id The account.
 * @property orgId The org.
 * @property provider The subscription.
 * @property label The name admins see (members see a neutral "Account N").
 * @property email The address the owner gave, if any.
 * @property providerAccountId The provider's account id (Claude account uuid, ChatGPT account id): one row per id.
 * @property ownerMemberId The member who shared it, or null for an account an admin added.
 * @property priority Higher first among equally loaded accounts.
 * @property status Whether it serves.
 * @property limits Its limits.
 * @property cooldownUntil Set after a provider refusal (429): no request before it.
 * @property createdAt When it was shared.
 */
data class SharedAccount(
    val id: Uuid,
    val orgId: Uuid,
    val provider: AccountProvider,
    val label: String,
    val email: String?,
    val providerAccountId: String,
    val ownerMemberId: Uuid?,
    val priority: Int,
    val status: AccountStatus,
    val limits: AccountLimits,
    val cooldownUntil: Instant?,
    val createdAt: Instant,
)

/**
 * OAuth tokens of an account; never logged, stored only sealed.
 *
 * @property accessToken The bearer sent to the provider.
 * @property refreshToken Renews it.
 * @property expiresAt When the access token expires.
 */
class AccountCredentials(val accessToken: String, val refreshToken: String, val expiresAt: Instant) {
    override fun toString(): String = "AccountCredentials([REDACTED])"
}

/**
 * One provider quota window as last observed.
 *
 * @property window `five_hour`, `seven_day`, or `model:<family>` for a model-scoped weekly window.
 * @property utilization Used share, 0..1.
 * @property resetsAt When it resets, if the provider said.
 * @property observedAt When it was read.
 */
data class QuotaWindow(val window: String, val utilization: Double, val resetsAt: Instant?, val observedAt: Instant) {
    /** Whether the observation still describes the window (it has not reset since). */
    fun isCurrent(now: Instant): Boolean = resetsAt == null || resetsAt > now
}

/** Validation of what admins and members send. */
object SharingRules {
    /** The most accounts per org. */
    const val MAX_ACCOUNTS: Int = 100

    private val label = Regex("[^\\p{Cntrl}]{1,120}")

    /** Refuses a label that is empty, too long or has control characters. */
    fun requireLabel(value: String) {
        if (!label.matches(value)) throw InvalidAccountException("A label is 1 to 120 printable characters.")
    }

    /** Refuses limits out of range. */
    fun requireLimits(limits: AccountLimits) {
        if (limits.maxConcurrent !in 1..64) throw InvalidAccountException("maxConcurrent is 1 to 64.")
        if (!limits.utilizationCeiling.isFinite() || limits.utilizationCeiling !in 0.05..1.0) {
            throw InvalidAccountException("utilizationCeiling is 0.05 to 1.")
        }
        if (limits.monthlyBudgetMicros != null && limits.monthlyBudgetMicros < 0) throw InvalidAccountException("monthlyBudgetMicros is positive.")
        if ((limits.allowedMembers?.size ?: 0) > 1000) throw InvalidAccountException("At most 1000 allowed members.")
    }

    /** Refuses tokens that cannot be OAuth tokens. */
    fun requireTokens(accessToken: String, refreshToken: String, providerAccountId: String) {
        val token = Regex("[\\x21-\\x7e]{8,4096}")
        if (!token.matches(accessToken) || !token.matches(refreshToken)) throw InvalidAccountException("The tokens are malformed.")
        if (!Regex("[A-Za-z0-9._:@-]{1,128}").matches(providerAccountId)) throw InvalidAccountException("providerAccountId is malformed.")
    }
}

/** A malformed account. */
class InvalidAccountException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** The account does not exist in the org (or the caller may not see it). */
class AccountNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Account not found.")

/** Too many accounts. */
class TooManyAccountsException : OstromException(ErrorKind.CONFLICT, "An organization shares at most ${SharingRules.MAX_ACCOUNTS} accounts.")
