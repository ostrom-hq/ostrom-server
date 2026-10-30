package dev.ostrom.server.sharing.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The tokens of the shared accounts, used by the pool only (SECURITY-AUDIT S2-21: they serve the gateway and the org's
 * cloud pods, never a member). Tokens are renewed [REFRESH_MARGIN] before they expire, one refresh per account at a
 * time; a refused refresh token, or an envelope that no longer opens, marks the account RECONNECT_REQUIRED.
 *
 * @param accounts Accounts and their sealed tokens.
 * @param cipher Opens and seals the tokens.
 * @param refresher Renews tokens.
 * @param clock The time.
 */
class AccountTokens(
    private val accounts: SharedAccountRepository,
    private val cipher: AccountTokenCipher,
    private val refresher: OAuthRefresher,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val locks = mutableMapOf<Uuid, Mutex>()

    /**
     * Fresh tokens of the account.
     *
     * @return The tokens, or null when the account can no longer serve (it is then retired, unless the provider is
     *   only unreachable).
     */
    suspend fun fresh(account: SharedAccount): AccountCredentials? = locked(account) {
        val stored = storedTokens(account)
        when {
            stored == null -> retire(account)
            stored.expiresAt > clock.now() + REFRESH_MARGIN -> stored
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
                val sealed = cipher.seal(result.credentials, account.orgId, account.id)
                accounts.putEnvelope(account.orgId, account.id, sealed)
                result.credentials
            }
            RefreshResult.Revoked -> retire(account)
            // Still valid for a few minutes: serve with it; expired: let the provider's 401 speak.
            RefreshResult.Unavailable -> stored.takeIf { it.expiresAt > clock.now() }
        }

    private suspend fun retire(account: SharedAccount): AccountCredentials? {
        accounts.setStatus(account.orgId, account.id, AccountStatus.RECONNECT_REQUIRED)
        return null
    }

    /** Timing. */
    companion object {
        /** Tokens are renewed this long before they expire. */
        val REFRESH_MARGIN: Duration = 5.minutes
    }
}
