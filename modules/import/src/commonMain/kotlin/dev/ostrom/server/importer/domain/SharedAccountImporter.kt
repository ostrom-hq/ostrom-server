// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import dev.ostrom.server.sharing.domain.ShareAccountPayload
import dev.ostrom.server.sharing.domain.ShareAccountUseCase
import dev.ostrom.server.sharing.domain.SharedAccount
import dev.ostrom.server.sharing.domain.UpdateAccountPayload
import dev.ostrom.server.sharing.domain.UpdateSharedAccountUseCase

/** TeamCode shares an account of a provider Ostrom does not pool. */
class UnknownAccountProviderException(provider: String) :
    OstromException(ErrorKind.INVALID_REQUEST, "Ostrom shares claude and codex accounts, not $provider.")

/**
 * [AccountImporter] through the sharing module's use cases, as the admin running the import (who then owns the
 * account; its members share it again from their machine to own it). Every run can be repeated after a failure
 * midway, so each one checks what the org has rather than trusting what an earlier run did.
 *
 * - An account the org does not share yet is shared, stored turned off in the same write when TeamCode had it
 *   disabled.
 * - An account waiting for a reconnection is revived with TeamCode's tokens (turned off the same way).
 * - A serving account TeamCode has disabled is turned off, its tokens kept: it is never left serving.
 * - Any other account is left alone: its tokens may have been refreshed since TeamCode last stored them, and the
 *   provider rotates refresh tokens, so TeamCode's older pair could disconnect it. An account turned off in Ostrom
 *   stays off.
 *
 * A dry run makes the same reads and the share's own checks, and writes nothing.
 *
 * @param share Shares an account, or replaces its tokens.
 * @param list Lists the org's accounts.
 * @param update Turns an account off.
 */
class SharedAccountImporter(
    private val share: ShareAccountUseCase,
    private val list: ListSharedAccountsUseCase,
    private val update: UpdateSharedAccountUseCase,
) : AccountImporter {
    override suspend fun import(caller: Caller, account: TeamCodeAccount, dryRun: Boolean): ImportAction {
        val provider = AccountProvider.entries.firstOrNull { it.wire == account.provider.trim().lowercase() }
            ?: throw UnknownAccountProviderException(account.provider)
        val existing = list(caller).map { it.account }
            .firstOrNull { it.provider == provider && it.providerAccountId == account.providerAccountId }
        val serving = existing?.takeIf { it.status == AccountStatus.READY }
        return when {
            existing == null || existing.status == AccountStatus.RECONNECT_REQUIRED -> {
                val done = if (existing == null) ImportAction.CREATED else ImportAction.UPDATED
                shared(caller, payload(provider, account), dryRun, done)
            }
            serving != null && account.disabled -> turnedOff(caller, serving, dryRun)
            else -> ImportAction.UNCHANGED
        }
    }

    private suspend fun shared(
        caller: Caller,
        payload: ShareAccountPayload,
        dryRun: Boolean,
        done: ImportAction,
    ): ImportAction {
        if (dryRun) share.check(caller, payload) else share(caller, payload)
        return if (dryRun) ImportAction.PLANNED else done
    }

    private suspend fun turnedOff(caller: Caller, account: SharedAccount, dryRun: Boolean): ImportAction {
        if (!dryRun) update(caller, account.id, UpdateAccountPayload(disabled = true))
        return if (dryRun) ImportAction.PLANNED else ImportAction.UPDATED
    }

    private fun payload(provider: AccountProvider, account: TeamCodeAccount) = ShareAccountPayload(
        provider = provider,
        label = account.label,
        email = account.email,
        providerAccountId = account.providerAccountId,
        accessToken = account.accessToken.reveal(),
        refreshToken = account.refreshToken.reveal(),
        expiresAt = account.expiresAt,
        disabled = account.disabled,
    )
}
