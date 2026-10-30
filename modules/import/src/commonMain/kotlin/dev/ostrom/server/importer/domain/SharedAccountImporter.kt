// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import dev.ostrom.server.sharing.domain.ShareAccountPayload
import dev.ostrom.server.sharing.domain.ShareAccountUseCase
import dev.ostrom.server.sharing.domain.UpdateAccountPayload
import dev.ostrom.server.sharing.domain.UpdateSharedAccountUseCase

/** TeamCode shares an account of a provider Ostrom does not pool. */
class UnknownAccountProviderException(provider: String) :
    OstromException(ErrorKind.INVALID_REQUEST, "Ostrom shares claude and codex accounts, not $provider.")

/**
 * [AccountImporter] through the sharing module's use cases, as the admin running the import (who then owns the
 * account; its members share it again from their machine to own it).
 *
 * An account the org already shares is left alone: its tokens may have been refreshed since TeamCode last stored them,
 * and the provider rotates refresh tokens, so TeamCode's older pair could disconnect it.
 *
 * @param share Shares an account.
 * @param list Lists the org's accounts.
 * @param update Turns an account TeamCode had disabled off.
 */
class SharedAccountImporter(
    private val share: ShareAccountUseCase,
    private val list: ListSharedAccountsUseCase,
    private val update: UpdateSharedAccountUseCase,
) : AccountImporter {
    override suspend fun import(caller: Caller, account: TeamCodeAccount): ImportAction {
        val provider = AccountProvider.entries.firstOrNull { it.wire == account.provider }
            ?: throw UnknownAccountProviderException(account.provider)
        val shared = list(caller).any {
            it.account.provider == provider && it.account.providerAccountId == account.providerAccountId
        }
        if (shared) return ImportAction.UNCHANGED
        val created = share(
            caller,
            ShareAccountPayload(
                provider = provider,
                label = account.label,
                email = account.email,
                providerAccountId = account.providerAccountId,
                accessToken = account.accessToken.reveal(),
                refreshToken = account.refreshToken.reveal(),
                expiresAt = account.expiresAt,
            ),
        )
        if (account.disabled) update(caller, created.id, UpdateAccountPayload(disabled = true))
        return ImportAction.CREATED
    }
}
