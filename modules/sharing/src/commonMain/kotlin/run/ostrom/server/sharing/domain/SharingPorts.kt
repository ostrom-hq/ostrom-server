// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Storage of shared accounts and their sealed tokens. Every read and write is confined to one org. */
interface SharedAccountRepository {
    /**
     * Inserts, or replaces the tokens, label and email of the same provider account in the org (its limits kept);
     * returns the stored row.
     *
     * @param expectedVersion Writes only if the org's row of that provider account is still at this version (0: only
     *   if it has none; above 0, never if it was deleted meanwhile); null writes whatever it is.
     *
     * @throws AccountOwnedByAnotherMemberException When another member owns that provider account meanwhile.
     * @throws AccountChangedException When [expectedVersion] is set and the row is no longer at it.
     */
    suspend fun upsert(account: SharedAccount, envelope: String, expectedVersion: Long? = null): SharedAccount

    /** The account. */
    suspend fun get(orgId: Uuid, id: Uuid): SharedAccount?

    /** The org's accounts, by priority then label. */
    suspend fun list(orgId: Uuid): List<SharedAccount>

    /** The org's accounts of a provider that are READY. */
    suspend fun ready(orgId: Uuid, provider: AccountProvider): List<SharedAccount>

    /**
     * Changes label, priority, status and limits; null when absent, or no longer at [expectedVersion] when it is set.
     */
    suspend fun update(account: SharedAccount, expectedVersion: Long? = null): SharedAccount?

    /** Deletes; false when absent. */
    suspend fun delete(orgId: Uuid, id: Uuid): Boolean

    /** The sealed tokens. */
    suspend fun envelope(orgId: Uuid, id: Uuid): String?

    /** Replaces the sealed tokens after a refresh. */
    suspend fun putEnvelope(orgId: Uuid, id: Uuid, envelope: String)

    /** Sets the status. */
    suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus)

    /** Sets the cool-down: no request before [until]. */
    suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant)
}

/** The provider quota windows last observed on each account; scoped by org like the accounts. */
interface QuotaWindowRepository {
    /** Replaces observed windows of the account (same name replaced, others kept). */
    suspend fun put(orgId: Uuid, id: Uuid, windows: List<QuotaWindow>)

    /** Observed windows of the org's accounts, by account. */
    suspend fun byAccount(orgId: Uuid): Map<Uuid, List<QuotaWindow>>
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
    /**
     * New tokens.
     *
     * @property credentials The new pair.
     * @property accountId The provider account the provider says the tokens belong to (Claude's `account.uuid`, the
     *   ChatGPT account id of Codex's `id_token`), or null when its answer does not say.
     */
    data class Renewed(val credentials: AccountCredentials, val accountId: String? = null) : RefreshResult

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
