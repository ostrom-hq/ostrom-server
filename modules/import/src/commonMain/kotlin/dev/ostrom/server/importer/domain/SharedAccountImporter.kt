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
 * - An account the org does not share yet is shared, then turned off when TeamCode had it disabled.
 * - An account TeamCode has disabled is turned off, whatever it is in Ostrom: it is never left serving.
 * - An account waiting for a reconnection is revived with TeamCode's tokens.
 * - Any other account is left alone: its tokens may have been refreshed since TeamCode last stored them, and the
 *   provider rotates refresh tokens, so TeamCode's older pair could disconnect it.
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
        val disable = account.disabled && existing?.status != AccountStatus.DISABLED
        val revive = !account.disabled && existing?.status == AccountStatus.RECONNECT_REQUIRED
        return when {
            existing != null && !disable && !revive -> ImportAction.UNCHANGED
            dryRun -> ImportAction.PLANNED
            else -> write(caller, provider, account, existing, disable)
        }
    }

    private suspend fun write(
        caller: Caller,
        provider: AccountProvider,
        account: TeamCodeAccount,
        existing: SharedAccount?,
        disable: Boolean,
    ): ImportAction {
        // A disabled account is turned off first when the org has it, and right after sharing it otherwise: if that
        // second call fails, the next run sees it serving and turns it off.
        val id = if (existing != null && disable) existing.id else share(caller, payload(provider, account)).id
        if (disable) update(caller, id, UpdateAccountPayload(disabled = true))
        return if (existing == null) ImportAction.CREATED else ImportAction.UPDATED
    }

    private fun payload(provider: AccountProvider, account: TeamCodeAccount) = ShareAccountPayload(
        provider = provider,
        label = account.label,
        email = account.email,
        providerAccountId = account.providerAccountId,
        accessToken = account.accessToken.reveal(),
        refreshToken = account.refreshToken.reveal(),
        expiresAt = account.expiresAt,
    )
}
