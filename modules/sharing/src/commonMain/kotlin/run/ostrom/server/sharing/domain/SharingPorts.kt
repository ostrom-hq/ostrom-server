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
     * @throws AccountRefreshingException When a replica is renewing that account's tokens now: new tokens never
     *   replace a pair whose refresh is in flight; they clear an intent older than [AccountTokens.PENDING_LIMIT].
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

    /** Sets the status. */
    suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus)

    /** Sets the cool-down: no request before [until]. */
    suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant)
}

/**
 * An account's tokens as its row holds them, read under the row's lock.
 *
 * @property status Whether it serves.
 * @property envelope The sealed tokens.
 * @property pending Whether a refresh was committed and has not ended: some replica sent, or is about to send, the
 *   refresh token to the provider.
 * @property stale Whether that refresh began more than [AccountTokens.PENDING_LIMIT] ago by the database's clock: its
 *   owner died or hung, and whether the provider rotated the token is unknown.
 * @property credentialVersion Bumped each time the tokens change (a refresh saved, a share).
 */
data class RefreshState(
    val status: AccountStatus,
    val envelope: String,
    val pending: Boolean,
    val stale: Boolean,
    val credentialVersion: Long,
)

/** What a look at a [RefreshState] writes, in the transaction that holds the row's lock. */
sealed interface RefreshWrite {
    /** Nothing. */
    data object Keep : RefreshWrite

    /** Commits the refresh intent (`refresh_pending`, its start by the database's clock): the caller owns it. */
    data object Claim : RefreshWrite

    /** Marks the account RECONNECT_REQUIRED, the intent kept: only new tokens (a share) clear it. */
    data object Retire : RefreshWrite

    /** Replaces the sealed tokens, their version unchanged (an access token the provider refused, marked expired). */
    data class Reseal(val envelope: String) : RefreshWrite
}

/**
 * The refresh coordination of the accounts, in the database so that every replica sees it (TeamCode's
 * `SqlCredentialSource`). Every call is confined to one org.
 */
interface AccountRefreshRepository {
    /**
     * Locks the account's row (`FOR UPDATE`), gives its state to [decide] (null when the account is gone), writes what
     * it answers, and commits.
     *
     * @return What [decide] returned beside its write.
     */
    suspend fun <T> locked(orgId: Uuid, id: Uuid, decide: suspend (RefreshState?) -> Pair<RefreshWrite, T>): T

    /**
     * Stores renewed tokens and ends the intent, only while the intent is still the one taken at [credentialVersion]
     * (a share meanwhile replaced the tokens and cleared it).
     *
     * @return Whether they were stored.
     */
    suspend fun save(orgId: Uuid, id: Uuid, credentialVersion: Long, envelope: String): Boolean

    /**
     * Sets [status] on an account whose refresh failed, the intent kept, only while it is still the one taken at
     * [credentialVersion].
     *
     * @return Whether it was set.
     */
    suspend fun fail(orgId: Uuid, id: Uuid, credentialVersion: Long, status: AccountStatus): Boolean
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
