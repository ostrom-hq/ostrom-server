// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The tokens of the shared accounts, used by the pool only (SECURITY-AUDIT S2-21: they serve the gateway and the org's
 * cloud pods, never a member). Tokens are renewed [REFRESH_MARGIN] before they expire.
 *
 * Providers rotate refresh tokens: one sent twice disconnects the account, and a row lock alone does not survive a pod
 * killed between the provider's answer and the write. So the refresh is coordinated in the database, as TeamCode does
 * it (`SqlCredentialSource`, docs/replicas.md): under the account row's lock, the replica that finds the tokens due
 * commits a refresh intent before it calls the provider, and owns the refresh. The others wait for it up to
 * [WAIT_TURNS] × [WAIT_TURN], then give up on the account for this lease (the pool tries the next one); they never send
 * the token again to take over. The owner stores the new pair only while its intent is still the current one (a share
 * meanwhile wins). Any failure (refused, unreachable, an answer that cannot be read, [REFRESH_TIMEOUT]) leaves the
 * provider's state unknown: the account is marked RECONNECT_REQUIRED, the intent kept, and only new tokens (its owner
 * sharing it again, an import) clear it. An intent older than [PENDING_LIMIT] by the database's clock is a dead
 * owner's: the next lease marks the account RECONNECT_REQUIRED. A request cancelled during a refresh does not cancel
 * it: an interrupted refresh would disconnect the account for nothing.
 *
 * A refresh also checks whose tokens they are: the account is shared under a provider account id its owner declared
 * (the CLI, an import), and when the provider names another account in its answer, the account is marked
 * WRONG_ACCOUNT and serves nothing, with a warning naming the account (never a token). The new pair is not stored: it
 * is another account's, whose refresh token the provider has rotated all the same. An answer that names no account is
 * not a refusal: the id is optional in the provider's answer, and refusing it would stop every account of the org
 * together.
 *
 * A long-lived token (no refresh token), or an envelope that no longer opens, marks the account RECONNECT_REQUIRED once
 * it is due.
 *
 * Refresh ownership ([RefreshOwnership], TeamCode's handover): an account waiting for its handover serves nothing, and
 * an access-only one serves its access token until it is due, then nothing, never sending its refresh token: its
 * source renews its own copy, and only an import with fresh tokens (or a handover) brings it back.
 *
 * @param refreshes The accounts' tokens and refresh intents.
 * @param cipher Opens and seals the tokens.
 * @param refresher Renews tokens.
 * @param clock The time of the tokens' expiry.
 * @param logger Where an account taken out of service is reported.
 */
class AccountTokens(
    private val refreshes: AccountRefreshRepository,
    private val cipher: AccountTokenCipher,
    private val refresher: OAuthRefresher,
    private val clock: Clock,
    private val logger: Logger,
) {
    /** What a look at the row decided. */
    private sealed interface Step {
        /** Serve with these. */
        class Use(val credentials: AccountCredentials) : Step

        /** This replica owns the refresh of [stored], intent taken at [credentialVersion]. */
        class Own(val stored: AccountCredentials, val credentialVersion: Long) : Step

        /** Another replica refreshes: look again shortly. */
        data object Wait : Step

        /** The tokens changed during this replica's refresh (a share): look again at once. */
        data object Again : Step

        /** The account serves nothing now. */
        data object Gone : Step
    }

    /**
     * Fresh tokens of the account.
     *
     * @return The tokens, or null when the account cannot serve this lease (retired, gone, or still being refreshed by
     *   another replica).
     */
    suspend fun fresh(account: SharedAccount): AccountCredentials? {
        var step: Step = Step.Again
        var turns = 0
        while ((step == Step.Again || step == Step.Wait) && turns < WAIT_TURNS) {
            if (step == Step.Wait) delay(WAIT_TURN)
            turns++
            step = refreshes.locked(account.orgId, account.id) { look(account, it) }
            if (step is Step.Own) step = renew(account, step)
        }
        return (step as? Step.Use)?.credentials
    }

    /**
     * Forces a refresh at the next lease: the provider refused [rejected]'s access token before its expiry. Tokens a
     * refresh already replaced, or one in progress, are left alone.
     */
    suspend fun expire(account: SharedAccount, rejected: AccountCredentials) {
        refreshes.locked(account.orgId, account.id) { state ->
            val stored = state?.takeIf { !it.pending }?.let { cipher.open(it.envelope, account.orgId, account.id) }
            if (stored == null || stored.accessToken != rejected.accessToken) {
                RefreshWrite.Keep to Unit
            } else {
                val expired = AccountCredentials(stored.accessToken, stored.refreshToken, EXPIRED)
                RefreshWrite.Reseal(cipher.seal(expired, account.orgId, account.id)) to Unit
            }
        }
    }

    private suspend fun look(account: SharedAccount, state: RefreshState?): Pair<RefreshWrite, Step> {
        val stored = state?.takeIf { it.status == AccountStatus.READY && !it.pending }
            ?.let { cipher.open(it.envelope, account.orgId, account.id) }
        return when {
            state == null || state.status != AccountStatus.READY -> RefreshWrite.Keep to Step.Gone
            state.ownership == RefreshOwnership.PENDING_HANDOVER -> RefreshWrite.Keep to Step.Gone
            state.pending && state.stale -> RefreshWrite.Retire to Step.Gone
            state.pending -> RefreshWrite.Keep to Step.Wait
            stored == null -> RefreshWrite.Retire to Step.Gone
            stored.expiresAt > clock.now() + REFRESH_MARGIN -> RefreshWrite.Keep to Step.Use(stored)
            // Never renewed here: its source holds the refresh token, and sending it would disconnect the source.
            state.ownership == RefreshOwnership.ACCESS_ONLY -> RefreshWrite.Keep to Step.Gone
            // A long-lived token has nothing to renew it with: refused or expired, its owner enrolls it again.
            stored.refreshToken.isEmpty() -> RefreshWrite.Retire to Step.Gone
            else -> RefreshWrite.Claim to Step.Own(stored, state.credentialVersion)
        }
    }

    // The intent is committed: whatever happens to the request, the refresh runs to its end and records it.
    private suspend fun renew(account: SharedAccount, own: Step.Own): Step = withContext(NonCancellable) {
        val recorded = try {
            record(account, own, withTimeoutOrNull(REFRESH_TIMEOUT) { refresher.refresh(account.provider, own.stored) })
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The refresher, the cipher or the database failing leaves the provider's state as unknown as a refusal.
            fail(account, own, AccountStatus.RECONNECT_REQUIRED)
        }
        // Not recorded: the owner shared the account again meanwhile, and its new tokens are the ones to use.
        recorded ?: Step.Again
    }

    private suspend fun record(account: SharedAccount, own: Step.Own, result: RefreshResult?): Step? {
        val named = (result as? RefreshResult.Renewed)?.accountId
        return when {
            result !is RefreshResult.Renewed -> fail(account, own, AccountStatus.RECONNECT_REQUIRED)
            named != null && !SharingRules.sameProviderAccount(account.providerAccountId, named) ->
                fail(account, own, AccountStatus.WRONG_ACCOUNT)?.also { warnWrongAccount(account) }
            else -> {
                val envelope = cipher.seal(result.credentials, account.orgId, account.id)
                Step.Use(result.credentials)
                    .takeIf { refreshes.save(account.orgId, account.id, own.credentialVersion, envelope) }
            }
        }
    }

    private suspend fun fail(account: SharedAccount, own: Step.Own, status: AccountStatus): Step? =
        Step.Gone.takeIf { refreshes.fail(account.orgId, account.id, own.credentialVersion, status) }

    private fun warnWrongAccount(account: SharedAccount) {
        logger.warn(
            "A shared account's tokens belong to another provider account: it serves nothing until shared again.",
            mapOf(
                "org_id" to account.orgId.toString(),
                "account_id" to account.id.toString(),
                "provider" to account.provider.wire,
            ),
        )
    }

    /** Timing. */
    companion object {
        private val EXPIRED = Instant.fromEpochMilliseconds(0)

        /** Tokens are renewed this long before they expire. */
        val REFRESH_MARGIN: Duration = 5.minutes

        /** The longest a refresh may take, the provider's answer included. */
        val REFRESH_TIMEOUT: Duration = 45.seconds

        /** A refresh intent older than this is its dead owner's (TeamCode's 60 seconds). */
        val PENDING_LIMIT: Duration = 60.seconds

        /** How long a replica waits between two looks at an account another one refreshes. */
        val WAIT_TURN: Duration = 100.milliseconds

        /** How many looks a replica takes before it gives up on the account for this lease (TeamCode's 400). */
        const val WAIT_TURNS: Int = 400
    }
}
