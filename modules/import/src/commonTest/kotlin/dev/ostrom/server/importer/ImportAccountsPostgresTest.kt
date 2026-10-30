// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import dev.ostrom.server.importer.Importing.adminA
import dev.ostrom.server.importer.domain.ImportAction.CREATED
import dev.ostrom.server.importer.domain.ImportAction.SKIPPED
import dev.ostrom.server.importer.domain.ImportAction.UNCHANGED
import dev.ostrom.server.importer.presentation.TeamCodeAccountDto
import dev.ostrom.server.importer.presentation.TeamCodeImportRequest
import dev.ostrom.server.sharing.SharingModule
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import dev.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** With the sharing module installed, TeamCode's shared accounts become the org's, through its use cases. */
class ImportAccountsPostgresTest {
    private val modules = Importing.modules + SharingModule

    private fun account(provider: String, id: String, disabled: Boolean = false) = TeamCodeAccountDto(
        provider = provider,
        providerAccountId = id,
        label = "Lyes $id",
        email = "lyes@example.com",
        accessToken = "access-$id-token",
        refreshToken = "refresh-$id-token",
        expiresAt = "2026-11-01T00:00:00Z",
        disabled = disabled,
    )

    @Test
    fun teamCodesAccountsAreSharedOnceAndNeverOverwritten() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val request = TeamCodeImportRequest(
                accounts = listOf(
                    account("claude", "claude-1"),
                    account("codex", "codex-1", disabled = true),
                    account("gemini", "gemini-1"),
                ),
            )
            val unknown = "Ostrom shares claude and codex accounts, not gemini."
            assertEquals(
                listOf(
                    line("account", "claude/Lyes claude-1", CREATED),
                    line("account", "codex/Lyes codex-1", CREATED),
                    line("account", "gemini/Lyes gemini-1", SKIPPED, unknown),
                ),
                report(request).items.filter { it.kind == "account" },
            )
            val shared = koin.get<ListSharedAccountsUseCase>()(adminA).associateBy { it.account.providerAccountId }
            assertEquals(AccountProvider.CLAUDE, shared.getValue("claude-1").account.provider)
            assertEquals(AccountStatus.READY, shared.getValue("claude-1").account.status)
            assertEquals(AccountStatus.DISABLED, shared.getValue("codex-1").account.status, "Disabled in TeamCode")
            assertEquals(adminA.memberId, shared.getValue("claude-1").account.ownerMemberId)

            // Shared already: left alone, its tokens included; another account of the same provider is added.
            val twice = listOf(account("claude", "claude-1"), account("claude", "claude-2"))
            assertEquals(
                listOf(
                    line("account", "claude/Lyes claude-1", UNCHANGED),
                    line("account", "claude/Lyes claude-2", CREATED),
                ),
                report(TeamCodeImportRequest(accounts = twice)).items.filter { it.kind == "account" },
            )
            assertEquals(3, koin.get<ListSharedAccountsUseCase>()(adminA).size)
        }
    }
}
