// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.sharing.domain.AccountChangedException
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import run.ostrom.server.sharing.domain.OAuthRefresher
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.sharing.domain.ShareAccountPayload
import run.ostrom.server.sharing.domain.ShareAccountUseCase
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharingRules
import run.ostrom.server.sharing.domain.TooManyAccountsException
import run.ostrom.server.sharing.domain.UpdateAccountPayload
import run.ostrom.server.sharing.domain.UpdateSharedAccountUseCase
import kotlin.uuid.Uuid

/** TeamCode shares an account of a provider Ostrom does not pool. */
class UnknownAccountProviderException(provider: String) :
    OstromException(ErrorKind.INVALID_REQUEST, "Ostrom shares claude and codex accounts, not $provider.")

/** The provider refused TeamCode's tokens of an account waiting for a reconnection. */
class TeamCodeTokensRefusedException :
    OstromException(
        ErrorKind.CONFLICT,
        "TeamCode's tokens for this account are refused too: it keeps waiting for its owner to sign in again.",
    )

/**
 * TeamCode's tokens for an account waiting for a reconnection are another provider account's, or the provider did not
 * say whose they are: an export edited by hand must never put someone else's subscription behind the org's account.
 */
class TeamCodeTokensOfAnotherAccountException :
    OstromException(
        ErrorKind.CONFLICT,
        "The provider does not confirm that TeamCode's tokens are this account's: it keeps waiting for its owner to " +
            "sign in again.",
    )

/** The provider could not be reached to test TeamCode's tokens. */
class TeamCodeTokensUntestedException :
    OstromException(
        ErrorKind.UNAVAILABLE,
        "The provider could not be reached to test TeamCode's tokens: the account keeps waiting. Run the import again.",
    )

/**
 * [AccountImporter] through the sharing module's use cases, as the admin running the import (who then owns the
 * account; its members share it again from their machine to own it). A run reads the org's accounts once and keeps
 * them up to date as it goes, so two entries for one account and the account cap count the run's earlier accounts,
 * in a dry run too. Every run can be repeated after a failure midway: each one checks what the org has rather than
 * trusting what an earlier run did. Provider and provider account id match without case or surrounding spaces.
 *
 * - An account the org does not share yet is shared, stored turned off in the same write when TeamCode had it
 *   disabled.
 * - An account waiting for a reconnection is left so while TeamCode runs ([ImportAction.REVIVE_AT_CUTOVER]): testing
 *   TeamCode's tokens rotates them, which would disconnect TeamCode. The cutover run revives it, only if TeamCode's
 *   tokens still renew and the provider's answer names that same provider account: they are renewed first (a dry run
 *   cannot: renewing rotates them), then stored, turned off in the same write when TeamCode had it disabled; its label
 *   and email stay the org's.
 * - A serving account TeamCode has disabled is turned off, its tokens kept: it is never left serving.
 * - Any other account is left alone: its tokens may have been refreshed since TeamCode last stored them, and the
 *   provider rotates refresh tokens, so TeamCode's older pair could disconnect it. An account turned off in Ostrom
 *   stays off.
 *
 * Every write names the version it read: a reconnection or an edit made meanwhile is never overwritten (the line is
 * skipped, and the next run sees the change).
 *
 * @param share Shares an account, or replaces its tokens.
 * @param list Lists the org's accounts.
 * @param update Turns an account off.
 * @param refresher Tests TeamCode's tokens before a revival.
 */
class SharedAccountImporter(
    private val share: ShareAccountUseCase,
    private val list: ListSharedAccountsUseCase,
    private val update: UpdateSharedAccountUseCase,
    private val refresher: OAuthRefresher,
) : AccountImporter {
    override suspend fun begin(caller: Caller, dryRun: Boolean, cutover: Boolean): AccountImportRun {
        val known = list(caller).map { Known(it.account) }.associateBy { it.key }.toMutableMap()
        return Run(caller, dryRun, cutover, known)
    }

    /** What the run knows of one of the org's accounts, as read or as it wrote (or would write) it. */
    private data class Known(
        val id: Uuid,
        val provider: AccountProvider,
        val providerAccountId: String,
        val label: String,
        val email: String?,
        val status: AccountStatus,
        val version: Long,
    ) {
        constructor(account: SharedAccount) : this(
            account.id,
            account.provider,
            account.providerAccountId,
            account.label,
            account.email,
            account.status,
            account.version,
        )

        val key get() = keyOf(provider, providerAccountId)
    }

    /** What a run does for one of TeamCode's accounts. */
    private sealed interface Change {
        /** Shares an account the org does not have. */
        class Create(val payload: ShareAccountPayload) : Change

        /** Shares again an account waiting for a reconnection. */
        class Revive(val known: Known, val payload: ShareAccountPayload) : Change

        /** Turns off a serving account. */
        class TurnOff(val known: Known) : Change
    }

    private inner class Run(
        private val caller: Caller,
        private val dryRun: Boolean,
        private val cutover: Boolean,
        private val known: MutableMap<Pair<AccountProvider, String>, Known>,
    ) : AccountImportRun {
        override suspend fun import(account: TeamCodeAccount): ImportAction {
            val provider = AccountProvider.entries.firstOrNull { it.wire == account.provider.trim().lowercase() }
                ?: throw UnknownAccountProviderException(account.provider)
            val key = keyOf(provider, account.providerAccountId)
            val existing = known[key]
            return when {
                existing != null && existing.status.awaitsSignIn && !cutover -> ImportAction.REVIVE_AT_CUTOVER
                else -> {
                    val change = change(provider, account, existing)
                    if (change == null) ImportAction.UNCHANGED else carryOut(key, change)
                }
            }
        }

        private suspend fun carryOut(key: Pair<AccountProvider, String>, change: Change): ImportAction {
            refuseLikeTheShare(change)
            return if (dryRun) {
                known[key] = planned(change)
                ImportAction.PLANNED
            } else {
                known[key] = write(change)
                if (change is Change.Create) ImportAction.CREATED else ImportAction.UPDATED
            }
        }

        private fun change(provider: AccountProvider, account: TeamCodeAccount, existing: Known?): Change? = when {
            existing == null -> Change.Create(payload(provider, account, account.providerAccountId.trim(), 0))
            existing.status.awaitsSignIn -> {
                val payload = payload(provider, account, existing.providerAccountId, existing.version)
                Change.Revive(existing, payload.copy(label = existing.label, email = existing.email))
            }
            existing.status == AccountStatus.READY && account.disabled -> Change.TurnOff(existing)
            else -> null
        }

        /** Refuses what the write would refuse, before writing or in place of it in a dry run. */
        private suspend fun refuseLikeTheShare(change: Change) {
            when (change) {
                is Change.Create -> {
                    if (known.size >= SharingRules.MAX_ACCOUNTS) throw TooManyAccountsException()
                    share.check(caller, change.payload)
                }
                is Change.Revive -> share.check(caller, change.payload)
                is Change.TurnOff -> Unit
            }
        }

        private fun planned(change: Change): Known = when (change) {
            is Change.Create -> change.payload.let {
                Known(Uuid.random(), it.provider, it.providerAccountId, it.label, it.email, statusOf(it), 0)
            }
            is Change.Revive -> change.known.copy(status = statusOf(change.payload))
            is Change.TurnOff -> change.known.copy(status = AccountStatus.DISABLED)
        }

        private suspend fun write(change: Change): Known = when (change) {
            is Change.Create -> Known(share(caller, change.payload))
            is Change.Revive -> Known(revived(change))
            is Change.TurnOff -> Known(
                update(
                    caller,
                    change.known.id,
                    UpdateAccountPayload(disabled = true, ifVersion = change.known.version),
                ),
            )
        }

        /**
         * Shares [change] again with TeamCode's tokens renewed. Renewing rotated TeamCode's pair, so the renewed one is
         * the only one left: when the account changed meanwhile but still waits for a reconnection (an edit), it is
         * stored at the new version, with the account's new label and email; otherwise (reconnected, deleted) the
         * change is refused and the account is left as it is.
         */
        private suspend fun revived(change: Change.Revive): SharedAccount {
            val renewed = renewed(change.payload)
            return try {
                share(caller, renewed)
            } catch (changed: AccountChangedException) {
                val waiting = list(caller).firstOrNull {
                    it.account.id == change.known.id && it.account.status.awaitsSignIn
                } ?: throw changed
                val now = waiting.account
                share(caller, renewed.copy(label = now.label, email = now.email, ifVersion = now.version))
            }
        }
    }

    /**
     * [payload] with TeamCode's tokens renewed, so a pair the provider already refused, or one the provider does not
     * say is this account's, is never stored. A refused pair is lost (renewing rotated it): only someone who already
     * holds that refresh token can send it, and the account keeps waiting for its owner, as it did.
     */
    private suspend fun renewed(payload: ShareAccountPayload): ShareAccountPayload {
        val credentials = AccountCredentials(payload.accessToken, payload.refreshToken, payload.expiresAt)
        val renewed = when (val result = refresher.refresh(payload.provider, credentials)) {
            is RefreshResult.Renewed -> result.ofAccount(payload).credentials
            RefreshResult.Revoked -> throw TeamCodeTokensRefusedException()
            RefreshResult.Unavailable -> throw TeamCodeTokensUntestedException()
        }
        return payload.copy(
            accessToken = renewed.accessToken,
            refreshToken = renewed.refreshToken,
            expiresAt = renewed.expiresAt,
        )
    }

    /** This renewal, when the provider says the tokens are [payload]'s account's. */
    private fun RefreshResult.Renewed.ofAccount(payload: ShareAccountPayload): RefreshResult.Renewed {
        // Unlike the pool's refresh, a revival needs the provider to name the account: it brings back an account on
        // tokens nobody checked yet.
        val owner = accountId ?: throw TeamCodeTokensOfAnotherAccountException()
        if (!SharingRules.sameProviderAccount(payload.providerAccountId, owner)) {
            throw TeamCodeTokensOfAnotherAccountException()
        }
        return this
    }

    private companion object {
        fun keyOf(provider: AccountProvider, providerAccountId: String) =
            provider to providerAccountId.trim().lowercase()

        fun statusOf(payload: ShareAccountPayload) =
            if (payload.disabled) AccountStatus.DISABLED else AccountStatus.READY

        fun payload(provider: AccountProvider, account: TeamCodeAccount, providerAccountId: String, version: Long) =
            ShareAccountPayload(
                provider = provider,
                label = account.label,
                email = account.email,
                providerAccountId = providerAccountId,
                accessToken = account.accessToken.reveal(),
                refreshToken = account.refreshToken.reveal(),
                expiresAt = account.expiresAt,
                disabled = account.disabled,
                ifVersion = version,
            )
    }
}
