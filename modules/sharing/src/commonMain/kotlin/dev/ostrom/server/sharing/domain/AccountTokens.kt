// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.domain

import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The tokens of the shared accounts, used by the pool only (SECURITY-AUDIT S2-21: they serve the gateway and the org's
 * cloud pods, never a member). Tokens are renewed [REFRESH_MARGIN] before they expire, one refresh per account at a
 * time; a refused refresh token, or an envelope that no longer opens, marks the account RECONNECT_REQUIRED.
 *
 * A refresh also checks whose tokens they are: the account is shared under a provider account id its owner declared
 * (the CLI, an import), and when the provider names another account in its answer, the account is marked
 * WRONG_ACCOUNT and serves nothing, with a warning naming the account (never a token). The new pair is not stored: it
 * is another account's, whose refresh token the provider has rotated all the same. When the owner shared the account
 * again during the refresh, the new tokens are used and the status is left alone, as after a refused refresh. An
 * answer that names no account is not a refusal: the id is optional in the provider's answer, and refusing it would
 * stop every account of the org together.
 *
 * @param accounts Accounts and their sealed tokens.
 * @param cipher Opens and seals the tokens.
 * @param refresher Renews tokens.
 * @param clock The time.
 * @param logger Where an account taken out of service is reported.
 */
class AccountTokens(
    private val accounts: SharedAccountRepository,
    private val cipher: AccountTokenCipher,
    private val refresher: OAuthRefresher,
    private val clock: Clock,
    private val logger: Logger,
) {
    private val mutex = Mutex()
    private val locks = mutableMapOf<Uuid, Mutex>()

    // When the provider last could not be reached, per account: no refresh is tried again before UNAVAILABLE_BACKOFF,
    // so a provider that is down does not hold every lease of the account for its timeout.
    private val unavailableUntil = mutableMapOf<Uuid, Instant>()

    /**
     * Fresh tokens of the account.
     *
     * @return The tokens, or null when the account can no longer serve (it is then retired, unless the provider is
     *   only unreachable).
     */
    suspend fun fresh(account: SharedAccount): AccountCredentials? = locked(account) {
        val stored = storedTokens(account)
        val now = clock.now()
        when {
            stored == null -> retire(account)
            stored.expiresAt > now + REFRESH_MARGIN -> stored
            (unavailableUntil[account.id] ?: now) > now -> stored.takeIf { it.expiresAt > now }
            else -> renew(account, stored)
        }
    }

    /** Forces a refresh at the next lease: the provider refused the access token before its expiry. */
    suspend fun expire(account: SharedAccount) {
        locked(account) {
            storedTokens(account)?.let {
                val expired = AccountCredentials(it.accessToken, it.refreshToken, Instant.fromEpochMilliseconds(0))
                accounts.putEnvelope(account.orgId, account.id, cipher.seal(expired, account.orgId, account.id))
            }
        }
    }

    private suspend fun <T> locked(account: SharedAccount, block: suspend () -> T): T {
        val lock = mutex.withLock { locks.getOrPut(account.id) { Mutex() } }
        return lock.withLock { block() }
    }

    private suspend fun storedTokens(account: SharedAccount): AccountCredentials? =
        accounts.envelope(account.orgId, account.id)?.let { cipher.open(it, account.orgId, account.id) }

    private suspend fun renew(account: SharedAccount, stored: AccountCredentials): AccountCredentials? =
        when (val result = refresher.refresh(account.provider, stored)) {
            is RefreshResult.Renewed -> {
                unavailableUntil.remove(account.id)
                renewed(account, stored, result)
            }
            RefreshResult.Revoked -> rotatedMeanwhile(account, stored) ?: retire(account)
            // Still valid for a few minutes: serve with it; expired: let the provider's 401 speak.
            RefreshResult.Unavailable -> {
                unavailableUntil[account.id] = clock.now() + UNAVAILABLE_BACKOFF
                stored.takeIf { it.expiresAt > clock.now() }
            }
        }

    private suspend fun renewed(
        account: SharedAccount,
        used: AccountCredentials,
        result: RefreshResult.Renewed,
    ): AccountCredentials? {
        val named = result.accountId
        if (named != null && !SharingRules.sameProviderAccount(account.providerAccountId, named)) {
            return rotatedMeanwhile(account, used) ?: wrongAccount(account)
        }
        accounts.putEnvelope(account.orgId, account.id, cipher.seal(result.credentials, account.orgId, account.id))
        return result.credentials
    }

    /**
     * The tokens another replica (or the owner's CLI, by sharing again) stored while this one refreshed: the provider
     * rotates refresh tokens, so the old one is refused although the account is healthy.
     */
    private suspend fun rotatedMeanwhile(account: SharedAccount, used: AccountCredentials): AccountCredentials? =
        storedTokens(account)?.takeIf { it.refreshToken != used.refreshToken }

    private suspend fun wrongAccount(account: SharedAccount): AccountCredentials? {
        accounts.setStatus(account.orgId, account.id, AccountStatus.WRONG_ACCOUNT)
        logger.warn(
            "A shared account's tokens belong to another provider account: it serves nothing until shared again.",
            mapOf(
                "org_id" to account.orgId.toString(),
                "account_id" to account.id.toString(),
                "provider" to account.provider.wire,
            ),
        )
        return null
    }

    private suspend fun retire(account: SharedAccount): AccountCredentials? {
        accounts.setStatus(account.orgId, account.id, AccountStatus.RECONNECT_REQUIRED)
        return null
    }

    /** Timing. */
    companion object {
        /** Tokens are renewed this long before they expire. */
        val REFRESH_MARGIN: Duration = 5.minutes

        /** No refresh is tried again this long after the provider could not be reached. */
        val UNAVAILABLE_BACKOFF: Duration = 30.seconds
    }
}
