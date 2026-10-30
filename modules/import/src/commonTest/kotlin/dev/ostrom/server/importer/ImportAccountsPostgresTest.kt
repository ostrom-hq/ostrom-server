// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import dev.ostrom.server.importer.Importing.adminA
import dev.ostrom.server.importer.domain.ImportAction
import dev.ostrom.server.importer.domain.ImportAction.CREATED
import dev.ostrom.server.importer.domain.ImportAction.PLANNED
import dev.ostrom.server.importer.domain.ImportAction.SKIPPED
import dev.ostrom.server.importer.domain.ImportAction.UNCHANGED
import dev.ostrom.server.importer.domain.ImportAction.UPDATED
import dev.ostrom.server.importer.presentation.ImportItemDto
import dev.ostrom.server.importer.presentation.TeamCodeAccountDto
import dev.ostrom.server.importer.presentation.TeamCodeImportRequest
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.sharing.SharingModule
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.AccountTokenCipher
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCaseImpl
import dev.ostrom.server.sharing.domain.NoAccountSpendReader
import dev.ostrom.server.sharing.domain.ShareAccountPayload
import dev.ostrom.server.sharing.domain.ShareAccountUseCase
import dev.ostrom.server.sharing.domain.SharedAccount
import dev.ostrom.server.sharing.domain.SharedAccountRepository
import dev.ostrom.server.sharing.domain.SharedAccountView
import dev.ostrom.server.sharing.domain.UpdateAccountPayload
import dev.ostrom.server.sharing.domain.UpdateSharedAccountUseCase
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** With the sharing module installed, TeamCode's shared accounts become the org's, through its use cases. */
class ImportAccountsPostgresTest {
    private val modules = Importing.modules + SharingModule

    private fun account(provider: String, id: String, disabled: Boolean = false, run: Int = 1) = TeamCodeAccountDto(
        provider = provider,
        providerAccountId = id,
        label = "Lyes $id",
        email = "lyes@example.com",
        accessToken = "access-$id-token-$run",
        refreshToken = "refresh-$id-token-$run",
        expiresAt = "2026-11-01T00:00:00Z",
        disabled = disabled,
    )

    private suspend fun TestServer.accounts(): Map<String, SharedAccount> =
        koin.get<ListSharedAccountsUseCase>()(adminA).associate { it.account.providerAccountId to it.account }

    private suspend fun TestServer.statuses(vararg ids: String): List<AccountStatus> =
        accounts().let { shared -> ids.map { shared.getValue(it).status } }

    /** The access token the org stores for [accountId], deciphered. */
    private suspend fun TestServer.accessToken(accountId: String): String? {
        val id = accounts().getValue(accountId).id
        val envelope = koin.get<SharedAccountRepository>().envelope(adminA.orgId, id)!!
        return koin.get<AccountTokenCipher>().open(envelope, adminA.orgId, id)?.accessToken
    }

    private suspend fun TestServer.accountLines(vararg accounts: TeamCodeAccountDto, dryRun: Boolean = false) =
        report(TeamCodeImportRequest(accounts = accounts.toList()), dryRun = dryRun).items.filter {
            it.kind == "account"
        }

    private suspend fun TestServer.reconnectRequired(vararg ids: String) {
        val repository = koin.get<SharedAccountRepository>()
        val shared = accounts()
        for (id in ids) repository.setStatus(adminA.orgId, shared.getValue(id).id, AccountStatus.RECONNECT_REQUIRED)
    }

    @Test
    fun teamCodesAccountsAreSharedOnceAndNeverOverwritten() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val unknown = "Ostrom shares claude and codex accounts, not gemini."
            assertEquals(
                listOf(
                    line("account", "claude/Lyes claude-1", CREATED),
                    line("account", "codex/Lyes codex-1", CREATED),
                    line("account", "gemini/Lyes gemini-1", SKIPPED, unknown),
                    line("account", "claude/Lyes claude-3", CREATED),
                ),
                accountLines(
                    account("claude", "claude-1"),
                    account("codex", "codex-1", disabled = true),
                    account("gemini", "gemini-1"),
                    account(" CLAUDE", "claude-3").copy(providerAccountId = " claude-3 "),
                ),
            )
            val shared = accounts()
            assertEquals(AccountProvider.CLAUDE, shared.getValue("claude-3").provider, "Case and spaces are ignored")
            assertEquals(listOf(AccountStatus.READY, AccountStatus.DISABLED), statuses("claude-1", "codex-1"))
            assertEquals(adminA.memberId, shared.getValue("claude-1").ownerMemberId)
            assertEquals("access-claude-1-token-1", accessToken("claude-1"))

            // A second run, TeamCode's tokens having changed: what the org shares already is left alone.
            assertEquals(
                listOf(
                    line("account", "claude/Lyes claude-1", UNCHANGED),
                    line("account", "codex/Lyes codex-1", UNCHANGED),
                    line("account", "claude/Lyes claude-2", CREATED),
                ),
                accountLines(
                    account("claude", "claude-1", run = 2).copy(providerAccountId = " CLAUDE-1 "),
                    account("codex", "codex-1", disabled = true, run = 2),
                    account("claude", "claude-2", run = 2),
                ),
            )
            assertEquals("access-claude-1-token-1", accessToken("claude-1"), "Its tokens are not overwritten")
            assertEquals(4, accounts().size, "The same account in other case is the same account")
        }
    }

    @Test
    fun aRunCountsItsEarlierAccountsInADryRunTooTheCapAndDuplicatesIncluded() =
        serverTest(modules, importOverrides()) { server ->
            with(server) {
                val bulk = (1..98).map { account("claude", "bulk-$it") }.toTypedArray()
                assertEquals(98, accountLines(*bulk).count { it.action == "created" })
                val batch = arrayOf(
                    account("codex", "codex-1"),
                    account("codex", "codex-1", disabled = true),
                    account("codex", "codex-2", disabled = true),
                    account("codex", "codex-3"),
                    account("codex", "codex-2"),
                )
                val cap = line("account", "codex/Lyes codex-3", SKIPPED, "An organization shares at most 100 accounts.")
                fun lines(vararg actions: ImportAction): List<ImportItemDto> =
                    listOf("codex-1", "codex-1", "codex-2", "codex-3", "codex-2").zip(actions)
                        .map { (id, action) -> if (id == "codex-3") cap else line("account", "codex/Lyes $id", action) }

                assertEquals(
                    lines(PLANNED, PLANNED, PLANNED, SKIPPED, UNCHANGED),
                    accountLines(*batch, dryRun = true),
                    "Shared then turned off; shared off; over the cap; already off",
                )
                assertEquals(98, accounts().size, "A dry run writes nothing")
                assertEquals(lines(CREATED, UPDATED, CREATED, SKIPPED, UNCHANGED), accountLines(*batch))
                assertEquals(listOf(AccountStatus.DISABLED, AccountStatus.DISABLED), statuses("codex-1", "codex-2"))
            }
        }

    @Test
    fun aRerunTurnsOffWhatTeamCodeDisabledAndRevivesOnlyWhatStillRenews() =
        serverTest(modules, importOverrides()) { server ->
            with(server) {
                accountLines(*(1..5).map { account("claude", "claude-$it") }.toTypedArray())
                // claude-2 serves although TeamCode has since disabled it; the others lost their tokens.
                reconnectRequired("claude-1", "claude-3", "claude-4", "claude-5")
                val again = arrayOf(
                    account("claude", "claude-1", run = 2).copy(label = "Renamed in TeamCode"),
                    account("claude", "claude-2", disabled = true, run = 2),
                    account("claude", "claude-3", disabled = true, run = 2),
                    account("claude", "claude-4", run = 2).copy(refreshToken = "refused-claude-4-token"),
                    account("claude", "claude-5", run = 2).copy(refreshToken = "offline-claude-5-token"),
                    account("claude", "claude-6", run = 2).copy(label = ""),
                )
                val names = listOf("Renamed in TeamCode", "Lyes claude-2", "Lyes claude-3", "Lyes claude-4")
                    .map { "claude/$it" } + listOf("claude/Lyes claude-5", "claude/")
                val refused = "TeamCode's tokens for this account are refused too: it keeps waiting for its owner to " +
                    "sign in again."
                val untested = "The provider could not be reached to test TeamCode's tokens: the account keeps " +
                    "waiting. Run the import again."
                val unnamed = line("account", "claude/", SKIPPED, "A label is 1 to 120 printable characters.")
                val all = arrayOf("claude-1", "claude-2", "claude-3", "claude-4", "claude-5")
                val before = List(5) { if (it == 1) AccountStatus.READY else AccountStatus.RECONNECT_REQUIRED }

                assertEquals(
                    names.take(5).map { line("account", it, PLANNED) } + unnamed,
                    accountLines(*again, dryRun = true),
                    "A dry run makes the share's checks; it cannot test tokens (renewing rotates them)",
                )
                assertEquals(before, statuses(*all), "A dry run writes nothing")

                assertEquals(
                    names.take(3).map { line("account", it, UPDATED) } + listOf(
                        line("account", names[3], SKIPPED, refused),
                        line("account", names[4], SKIPPED, untested),
                        unnamed,
                    ),
                    accountLines(*again),
                )
                assertEquals(
                    listOf(AccountStatus.READY, AccountStatus.DISABLED, AccountStatus.DISABLED) +
                        List(2) { AccountStatus.RECONNECT_REQUIRED },
                    statuses(*all),
                    "Revived; turned off; revived off; refused tokens never stored; untested ones neither",
                )
                assertEquals("renewed-access-claude-1-token-2", accessToken("claude-1"), "TeamCode's, renewed")
                assertEquals("Lyes claude-1", accounts().getValue("claude-1").label, "Its label stays the org's")
                assertEquals("access-claude-2-token-1", accessToken("claude-2"), "Turning it off keeps its tokens")
                assertEquals("access-claude-4-token-1", accessToken("claude-4"))
                assertEquals(
                    names.take(3).map { line("account", it, UNCHANGED) },
                    accountLines(*again.take(3).toTypedArray()),
                    "Nothing left to do",
                )
            }
        }

    @Test
    fun aReconnectionOrAnEditMadeDuringTheRunIsNeverOverwritten() {
        val racing = module {
            single { RacingList(ListSharedAccountsUseCaseImpl(get(), get(), get(), NoAccountSpendReader(), get())) }
            single<ListSharedAccountsUseCase> { get<RacingList>() }
        }
        serverTest(modules, module { includes(importOverrides(), racing) }) { server ->
            with(server) {
                accountLines(account("claude", "claude-1"), account("claude", "claude-2"))
                reconnectRequired("claude-1")
                val claude2 = accounts().getValue("claude-2").id
                // Right after the run reads the org's accounts, claude-1's owner reconnects it and an admin renames
                // claude-2.
                koin.get<RacingList>().afterRead = {
                    val tokens = "owner-claude-1-token"
                    koin.get<ShareAccountUseCase>()(
                        adminA,
                        ShareAccountPayload(
                            AccountProvider.CLAUDE,
                            "Lyes claude-1",
                            null,
                            "claude-1",
                            "access-$tokens",
                            "refresh-$tokens",
                            Instant.parse("2026-11-01T00:00:00Z"),
                        ),
                    )
                    koin.get<UpdateSharedAccountUseCase>()(adminA, claude2, UpdateAccountPayload(label = "Renamed"))
                }
                val changed = "The account changed meanwhile: nothing was overwritten. Try again."
                assertEquals(
                    listOf(
                        line("account", "claude/Lyes claude-1", SKIPPED, changed),
                        line("account", "claude/Lyes claude-2", SKIPPED, changed),
                    ),
                    accountLines(account("claude", "claude-1", run = 2), account("claude", "claude-2", true, 2)),
                )
                assertEquals(listOf(AccountStatus.READY, AccountStatus.READY), statuses("claude-1", "claude-2"))
                assertEquals("access-owner-claude-1-token", accessToken("claude-1"), "The owner's reconnection stays")
                assertEquals("Renamed", accounts().getValue("claude-2").label, "The admin's edit stays")
            }
        }
    }

    /** The org's accounts, then [afterRead] once: a change made right after a run read them. */
    private class RacingList(private val list: ListSharedAccountsUseCase) : ListSharedAccountsUseCase {
        var afterRead: suspend () -> Unit = {}

        override suspend fun invoke(caller: Caller): List<SharedAccountView> {
            val read = list(caller)
            val hook = afterRead
            afterRead = {}
            hook()
            return read
        }
    }
}
