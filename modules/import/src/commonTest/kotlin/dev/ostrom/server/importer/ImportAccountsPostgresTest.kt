// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import dev.ostrom.server.importer.Importing.adminA
import dev.ostrom.server.importer.domain.ImportAction.CREATED
import dev.ostrom.server.importer.domain.ImportAction.PLANNED
import dev.ostrom.server.importer.domain.ImportAction.SKIPPED
import dev.ostrom.server.importer.domain.ImportAction.UNCHANGED
import dev.ostrom.server.importer.domain.ImportAction.UPDATED
import dev.ostrom.server.importer.presentation.TeamCodeAccountDto
import dev.ostrom.server.importer.presentation.TeamCodeImportRequest
import dev.ostrom.server.sharing.SharingModule
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.AccountTokenCipher
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import dev.ostrom.server.sharing.domain.SharedAccount
import dev.ostrom.server.sharing.domain.SharedAccountRepository
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

    /** The access token the org stores for [accountId], deciphered. */
    private suspend fun TestServer.accessToken(accountId: String): String? {
        val id = accounts().getValue(accountId).id
        val envelope = koin.get<SharedAccountRepository>().envelope(adminA.orgId, id)!!
        return koin.get<AccountTokenCipher>().open(envelope, adminA.orgId, id)?.accessToken
    }

    /** The statuses of claude-1, claude-2 and claude-3, and nothing else shared. */
    private suspend fun TestServer.statuses(): List<AccountStatus> = accounts().let { shared ->
        assertEquals(setOf("claude-1", "claude-2", "claude-3"), shared.keys)
        listOf("claude-1", "claude-2", "claude-3").map { shared.getValue(it).status }
    }

    private suspend fun TestServer.accountLines(vararg accounts: TeamCodeAccountDto, dryRun: Boolean = false) =
        report(TeamCodeImportRequest(accounts = accounts.toList()), dryRun = dryRun).items.filter {
            it.kind == "account"
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
                    line("account", " CLAUDE/Lyes claude-3", CREATED),
                ),
                accountLines(
                    account("claude", "claude-1"),
                    account("codex", "codex-1", disabled = true),
                    account("gemini", "gemini-1"),
                    account(" CLAUDE", "claude-3"),
                ),
            )
            val shared = accounts()
            assertEquals(AccountProvider.CLAUDE, shared.getValue("claude-1").provider)
            assertEquals(AccountProvider.CLAUDE, shared.getValue("claude-3").provider, "Case and spaces are ignored")
            assertEquals(AccountStatus.READY, shared.getValue("claude-1").status)
            assertEquals(AccountStatus.DISABLED, shared.getValue("codex-1").status, "Disabled in TeamCode")
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
                    account("claude", "claude-1", run = 2),
                    account("codex", "codex-1", disabled = true, run = 2),
                    account("claude", "claude-2", run = 2),
                ),
            )
            assertEquals("access-claude-1-token-1", accessToken("claude-1"), "Its tokens are not overwritten")
            assertEquals(4, accounts().size)
        }
    }

    @Test
    fun aRerunTurnsOffWhatTeamCodeDisabledAndRevivesWhatNeedsAReconnection() =
        serverTest(modules, importOverrides()) { server ->
            with(server) {
                accountLines(
                    account("claude", "claude-1"),
                    account("claude", "claude-2"),
                    account("claude", "claude-3"),
                )
                // claude-1 and claude-3 lost their tokens; claude-2 serves although TeamCode has since disabled it.
                val repository = koin.get<SharedAccountRepository>()
                for (id in listOf("claude-1", "claude-3")) {
                    repository.setStatus(adminA.orgId, accounts().getValue(id).id, AccountStatus.RECONNECT_REQUIRED)
                }
                val unnamed = account("claude", "claude-4", run = 2).copy(label = "")
                val again = arrayOf(
                    account("claude", "claude-1", run = 2),
                    account("claude", "claude-2", disabled = true, run = 2),
                    account("claude", "claude-3", disabled = true, run = 2),
                    unnamed,
                )
                val refused = line("account", "claude/", SKIPPED, "A label is 1 to 120 printable characters.")

                assertEquals(
                    listOf(
                        line("account", "claude/Lyes claude-1", PLANNED),
                        line("account", "claude/Lyes claude-2", PLANNED),
                        line("account", "claude/Lyes claude-3", PLANNED),
                        refused,
                    ),
                    accountLines(*again, dryRun = true),
                    "A dry run makes the same checks, the share's refusals included",
                )
                val before =
                    listOf(AccountStatus.RECONNECT_REQUIRED, AccountStatus.READY, AccountStatus.RECONNECT_REQUIRED)
                assertEquals(before, statuses(), "A dry run writes nothing")

                assertEquals(
                    listOf(
                        line("account", "claude/Lyes claude-1", UPDATED),
                        line("account", "claude/Lyes claude-2", UPDATED),
                        line("account", "claude/Lyes claude-3", UPDATED),
                        refused,
                    ),
                    accountLines(*again),
                )
                // Revived; turned off; revived and turned off in the same write.
                assertEquals(listOf(AccountStatus.READY, AccountStatus.DISABLED, AccountStatus.DISABLED), statuses())
                assertEquals("access-claude-1-token-2", accessToken("claude-1"), "With TeamCode's tokens")
                assertEquals("access-claude-2-token-1", accessToken("claude-2"), "Turning it off keeps its tokens")
                assertEquals(
                    listOf(
                        line("account", "claude/Lyes claude-2", UNCHANGED),
                        line("account", "claude/Lyes claude-3", UNCHANGED),
                    ),
                    accountLines(
                        account("claude", "claude-2", disabled = true, run = 3),
                        account("claude", "claude-3", disabled = true, run = 3),
                    ),
                    "Nothing left to do",
                )
            }
        }
}
