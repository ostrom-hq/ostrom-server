// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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

    /** The org's accounts of a provider that serve ([SharedAccount.serves]). */
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

    /**
     * Sets who renews the tokens, bumping the version, unless a refresh intent is on the row (in flight, or left
     * uncertain by a failure): only new tokens clear it.
     *
     * @return The account, or null when it is gone or carries a refresh intent.
     */
    suspend fun setOwnership(orgId: Uuid, id: Uuid, ownership: RefreshOwnership): SharedAccount?
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
 * @property ownership Who renews the tokens.
 */
data class RefreshState(
    val status: AccountStatus,
    val envelope: String,
    val pending: Boolean,
    val stale: Boolean,
    val credentialVersion: Long,
    val ownership: RefreshOwnership = RefreshOwnership.OSTROM,
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

/**
 * A request's slot on an account, held until it is released or [AccountSlotRepository.SLOT_LEASE] passes.
 *
 * @property id The slot.
 * @property accountId The account.
 */
data class AccountSlot(val id: Uuid, val accountId: Uuid)

/**
 * What a lease sees of the candidates while their rows are locked.
 *
 * @property active The unexpired slots of each candidate (absent: none).
 * @property remembered The account the member's session used last, while its affinity lasts, if any.
 * @property serving The candidates still serving now that their rows are locked (an account disabled or removed since
 *   the pool read it is not among them).
 */
data class SlotView(val active: Map<Uuid, Int>, val remembered: Uuid?, val serving: Set<Uuid>)

/**
 * The requests in flight on the accounts and the sessions' accounts, in the database so that every replica counts
 * them together (TeamCode's reservations and `conversation_affinities`, docs/replicas.md). Every call is confined to
 * one org; times are the database's clock.
 */
interface AccountSlotRepository {
    /**
     * In one transaction: locks the rows of [candidates], shows [pick] their slots and the session's remembered
     * account, takes a slot on the account it picks and remembers it for [session].
     *
     * @param memberId The member asking: affinities are the member's.
     * @param session The agent's session id, or null (no affinity read or written).
     * @param candidates The accounts that may serve, in the pool's order.
     * @param pick Picks one of [candidates], or null when none has room.
     *
     * @return The slot taken, or null when [pick] picked none.
     */
    suspend fun take(
        orgId: Uuid,
        memberId: Uuid,
        session: String?,
        candidates: List<SharedAccount>,
        pick: (SlotView) -> SharedAccount?,
    ): AccountSlot?

    /** Gives a slot back; nothing when it is gone. */
    suspend fun release(orgId: Uuid, slot: AccountSlot)

    /** Extends a slot still held by [SLOT_LEASE] from now; nothing when it is gone. */
    suspend fun renew(orgId: Uuid, slot: AccountSlot)

    /** The unexpired slots of the org's accounts (absent: none). */
    suspend fun active(orgId: Uuid): Map<Uuid, Int>

    /** The bounds. */
    companion object {
        /**
         * A slot never released (its replica died) frees itself after this (TeamCode's 15-minute lease); a request
         * still open renews its slot every [SLOT_RENEWAL], so a long stream keeps counting.
         */
        val SLOT_LEASE: Duration = 15.minutes

        /** How often an open request renews its slot. */
        val SLOT_RENEWAL: Duration = 5.minutes

        /** How long a session sticks to its account after its last lease. */
        val AFFINITY: Duration = 1.hours

        /** Sessions remembered at most per member: past it, a new session is placed without being remembered. */
        const val MAX_MEMBER_AFFINITIES: Int = 1_000

        /** Sessions remembered at most per org. */
        const val MAX_ORG_AFFINITIES: Int = 10_000
    }
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
