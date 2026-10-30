package dev.ostrom.server.sharing.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Storage of shared accounts, their sealed tokens and quota windows. Every read and write is scoped by org. */
interface SharedAccountRepository {
    /** Inserts, or replaces the tokens and label of the same provider account in the org; returns the stored row. */
    suspend fun upsert(account: SharedAccount, envelope: String): SharedAccount

    /** The account. */
    suspend fun get(orgId: Uuid, id: Uuid): SharedAccount?

    /** The org's accounts, by priority then label. */
    suspend fun list(orgId: Uuid): List<SharedAccount>

    /** The org's accounts of a provider that are READY. */
    suspend fun ready(orgId: Uuid, provider: AccountProvider): List<SharedAccount>

    /** Changes label, priority, status and limits; null when absent. */
    suspend fun update(account: SharedAccount): SharedAccount?

    /** Deletes; false when absent. */
    suspend fun delete(orgId: Uuid, id: Uuid): Boolean

    /** The sealed tokens. */
    suspend fun envelope(orgId: Uuid, id: Uuid): String?

    /** Replaces the sealed tokens after a refresh. */
    suspend fun putEnvelope(orgId: Uuid, id: Uuid, envelope: String)

    /** Sets the status. */
    suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus)

    /** Sets (or clears) the cool-down. */
    suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant?)

    /** Replaces observed windows of the account (same name replaced, others kept). */
    suspend fun putWindows(orgId: Uuid, id: Uuid, windows: List<QuotaWindow>)

    /** Observed windows of the org's accounts, by account. */
    suspend fun windows(orgId: Uuid): Map<Uuid, List<QuotaWindow>>
}

/** Renews OAuth tokens at the provider. */
interface OAuthRefresher {
    /**
     * Renews.
     *
     * @return The new tokens, [RefreshResult.Revoked] when the provider refused the refresh token (sign in again), or
     *   [RefreshResult.Unavailable] when the provider could not be reached (try later, keep the old tokens).
     */
    suspend fun refresh(provider: AccountProvider, credentials: AccountCredentials): RefreshResult
}

/** The result of a refresh. */
sealed interface RefreshResult {
    /** New tokens. */
    data class Renewed(val credentials: AccountCredentials) : RefreshResult

    /** Refused for good. */
    data object Revoked : RefreshResult

    /** Not now. */
    data object Unavailable : RefreshResult
}

/** Reads the provider's quota headers of a response. */
fun interface QuotaHeaderParser {
    /**
     * The windows the headers report.
     *
     * @param headers The response headers, names in lower case.
     */
    fun parse(provider: AccountProvider, headers: Map<String, String>, now: Instant): List<QuotaWindow>
}

/** What an account served this month, in equivalent API cost; bound to the usage ledger by the gateway. */
fun interface AccountSpendReader {
    /** USD millionths served by [accountId] since [since]. */
    suspend fun spentMicros(accountId: Uuid, since: Instant): Long
}

/** No spend known: per-account budgets never stop an account. The default until the usage ledger is bound. */
class NoAccountSpendReader : AccountSpendReader {
    override suspend fun spentMicros(accountId: Uuid, since: Instant): Long = 0
}
