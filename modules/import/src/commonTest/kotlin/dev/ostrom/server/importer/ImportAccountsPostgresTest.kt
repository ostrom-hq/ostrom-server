// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditEvent
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.importer.Importing.adminA
import dev.ostrom.server.importer.domain.ImportAction
import dev.ostrom.server.importer.domain.ImportAction.CREATED
import dev.ostrom.server.importer.domain.ImportAction.PLANNED
import dev.ostrom.server.importer.domain.ImportAction.REVIVE_AT_CUTOVER
import dev.ostrom.server.importer.domain.ImportAction.SKIPPED
import dev.ostrom.server.importer.domain.ImportAction.UNCHANGED
import dev.ostrom.server.importer.domain.ImportAction.UPDATED
import dev.ostrom.server.importer.infrastructure.IdentityImportAudit
import dev.ostrom.server.importer.presentation.ImportItemDto
import dev.ostrom.server.importer.presentation.TeamCodeAccountDto
import dev.ostrom.server.importer.presentation.TeamCodeImportRequest
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.sharing.SharingModule
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.AccountTokenCipher
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCaseImpl
import dev.ostrom.server.sharing.domain.NoAccountSpendReader
import dev.ostrom.server.sharing.domain.OAuthRefresher
import dev.ostrom.server.sharing.domain.RemoveSharedAccountUseCase
import dev.ostrom.server.sharing.domain.ShareAccountPayload
import dev.ostrom.server.sharing.domain.ShareAccountUseCase
import dev.ostrom.server.sharing.domain.SharedAccount
import dev.ostrom.server.sharing.domain.SharedAccountRepository
import dev.ostrom.server.sharing.domain.SharedAccountView
import dev.ostrom.server.sharing.domain.UpdateAccountPayload
import dev.ostrom.server.sharing.domain.UpdateSharedAccountUseCase
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.http.HttpStatusCode
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** With the sharing module installed, TeamCode's shared accounts become the org's, through its use cases. */
class ImportAccountsPostgresTest {
    private val modules = Importing.modules + SharingModule

    private companion object {
        const val WAITS = "This account waits for a reconnection. It is left so while TeamCode runs (testing " +
            "TeamCode's tokens would disconnect TeamCode): the cutover run revives it."
        const val REFUSED = "TeamCode's tokens for this account are refused too: it keeps waiting for its owner to " +
            "sign in again."
        const val UNTESTED = "The provider could not be reached to test TeamCode's tokens: the account keeps " +
            "waiting. Run the import again."
    }

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

    private suspend fun TestServer.accountLines(
        vararg accounts: TeamCodeAccountDto,
        dryRun: Boolean = false,
        cutover: Boolean = false,
    ): List<ImportItemDto> {
        val request = TeamCodeImportRequest(accounts = accounts.toList())
        val token = if (cutover) "owner-a" else "admin-a"
        return report(request, token, dryRun, cutover).items.filter { it.kind == "account" }
    }

    /** The import's bindings with an audit log that records what it is given. */
    private fun withAudit(audit: RecordingAuditLog) = module {
        includes(importOverrides())
        single<AuditLog> { audit }
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
    fun whileTeamCodeRunsNoTokenIsTestedAndTheCutoverRevivesOnlyWhatStillRenews() {
        val audit = RecordingAuditLog()
        serverTest(modules, withAudit(audit)) { server ->
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
                    .map { "claude/$it" } + listOf("claude/Lyes claude-5")
                val unnamed = line("account", "claude/", SKIPPED, "A label is 1 to 120 printable characters.")
                val all = arrayOf("claude-1", "claude-2", "claude-3", "claude-4", "claude-5")
                val waiting = AccountStatus.RECONNECT_REQUIRED
                fun lines(vararg actions: ImportAction, reasons: Map<Int, String> = emptyMap()) =
                    names.zip(actions).mapIndexed { index, (name, action) ->
                        line("account", name, action, reasons[index] ?: WAITS.takeIf { action == REVIVE_AT_CUTOVER })
                    } + unnamed

                // While TeamCode runs: a serving account is turned off; the others wait, their tokens untouched.
                val wait = REVIVE_AT_CUTOVER
                assertEquals(lines(wait, PLANNED, wait, wait, wait), accountLines(*again, dryRun = true))
                assertEquals(lines(wait, UPDATED, wait, wait, wait), accountLines(*again))
                val refresher = koin.get<OAuthRefresher>() as TeamCodeRefresher
                assertEquals(0, refresher.calls, "No token is tested at the provider while TeamCode runs")
                val parked = listOf(waiting, AccountStatus.DISABLED, waiting, waiting, waiting)
                assertEquals(parked, statuses(*all))

                // The cutover, TeamCode stopped: an owner's run, recorded; its dry run tests no token either.
                assertEquals(
                    lines(PLANNED, UNCHANGED, PLANNED, PLANNED, PLANNED),
                    accountLines(*again, dryRun = true, cutover = true),
                    "A dry run makes the share's checks; it cannot test tokens (renewing rotates them)",
                )
                assertEquals(listOf(0, 0), listOf(refresher.calls, audit.entries.size), "A dry run records nothing")
                assertEquals(parked, statuses(*all), "A dry run writes nothing")
                assertEquals(
                    lines(UPDATED, UNCHANGED, UPDATED, SKIPPED, SKIPPED, reasons = mapOf(3 to REFUSED, 4 to UNTESTED)),
                    accountLines(*again, cutover = true),
                )
                assertEquals(
                    listOf(AccountStatus.READY, AccountStatus.DISABLED, AccountStatus.DISABLED, waiting, waiting),
                    statuses(*all),
                    "Revived; off; revived off; refused tokens never stored; untested ones neither",
                )
                assertEquals("renewed-access-claude-1-token-2", accessToken("claude-1"), "TeamCode's, renewed")
                assertEquals("Lyes claude-1", accounts().getValue("claude-1").label, "Its label stays the org's")
                assertEquals("access-claude-2-token-1", accessToken("claude-2"), "Turning it off keeps its tokens")
                assertEquals("access-claude-4-token-1", accessToken("claude-4"))
                val event = audit.entries.single()
                assertEquals(IdentityImportAudit.CUTOVER, event.action)
                assertEquals(adminA.orgId, event.orgId)
                assertEquals("6", event.details["accounts"])
                assertEquals(
                    names.take(3).map { line("account", it, UNCHANGED) },
                    accountLines(*again.take(3).toTypedArray()),
                    "Nothing left to do",
                )
            }
        }
    }

    @Test
    fun theCutoverIsAuditedInTheOwnersNameAndTheReportCountsWhatWaitsForIt() {
        val audit = RecordingAuditLog()
        serverTest(modules, withAudit(audit)) { server ->
            with(server) {
                val request = TeamCodeImportRequest(accounts = (1..3).map { account("claude", "claude-$it") })
                report(request)
                reconnectRequired("claude-1", "claude-2")
                val normal = report(request)
                // Unchanged: claude-3, and the agent prefix the first run set to TeamCode's.
                assertEquals(
                    listOf(0, 0, 2, 0, 2),
                    listOf(normal.created, normal.updated, normal.unchanged, normal.skipped, normal.reviveAtCutover),
                )
                for (token in listOf("service-import", "cli-import")) {
                    val response = import(request, token, cutover = true)
                    assertEquals(HttpStatusCode.Forbidden, response.status, "A person runs the cutover, never $token")
                }
                assertEquals(emptyList(), audit.entries, "Nothing recorded for a refused cutover")
                val cutover = report(request, "owner-a", cutover = true)
                assertEquals(listOf(2, 2, 0), listOf(cutover.updated, cutover.unchanged, cutover.reviveAtCutover))
                val event = audit.entries.single()
                assertEquals(userOf("owner-a"), event.actorId, "The owner who ran it, as a user")
                assertEquals(adminA.orgId, event.orgId)
                assertEquals(IdentityImportAudit.CUTOVER, event.action)
                assertEquals("org" to "${adminA.orgId}", event.targetType to event.targetId)
                val member = "${Importing.ownerA.memberId}"
                assertEquals(mapOf("mode" to "cutover", "memberId" to member, "accounts" to "3"), event.details)
            }
        }
    }

    @Test
    fun onlyAnOwnerRunsTheCutoverAndOnlyWhereAnAuditLogRecordsIt() = serverTest(modules, importOverrides()) { server ->
        with(server) {
            val request = TeamCodeImportRequest(accounts = listOf(account("claude", "claude-1")))
            assertEquals(HttpStatusCode.Forbidden, import(request, "admin-a", dryRun = true, cutover = true).status)
            assertEquals(HttpStatusCode.ServiceUnavailable, import(request, "owner-a", cutover = true).status)
            assertEquals(emptyMap(), accounts(), "Nothing imported")
        }
    }

    @Test
    fun aReconnectionOrAnEditMadeDuringTheRunIsNeverOverwritten() {
        val racing = module {
            single { RacingList(ListSharedAccountsUseCaseImpl(get(), get(), get(), NoAccountSpendReader(), get())) }
            single<ListSharedAccountsUseCase> { get<RacingList>() }
        }
        serverTest(modules, module { includes(withAudit(RecordingAuditLog()), racing) }) { server ->
            with(server) {
                accountLines(account("claude", "claude-1"), account("claude", "claude-2"))
                reconnectRequired("claude-1")
                val claude2 = accounts().getValue("claude-2").id
                // Right after the run reads the org's accounts, claude-1's owner reconnects it and an admin renames
                // claude-2.
                koin.get<RacingList>().afterRead = {
                    koin.get<ShareAccountUseCase>()(adminA, owners("claude-1"))
                    koin.get<UpdateSharedAccountUseCase>()(adminA, claude2, UpdateAccountPayload(label = "Renamed"))
                }
                val changed = "The account changed meanwhile: nothing was overwritten. Try again."
                assertEquals(
                    listOf(
                        line("account", "claude/Lyes claude-1", SKIPPED, changed),
                        line("account", "claude/Lyes claude-2", SKIPPED, changed),
                    ),
                    accountLines(
                        account("claude", "claude-1", run = 2),
                        account("claude", "claude-2", true, 2),
                        cutover = true,
                    ),
                )
                assertEquals(listOf(AccountStatus.READY, AccountStatus.READY), statuses("claude-1", "claude-2"))
                assertEquals("access-owner-claude-1-token", accessToken("claude-1"), "The owner's reconnection stays")
                assertEquals("Renamed", accounts().getValue("claude-2").label, "The admin's edit stays")
            }
        }
    }

    @Test
    fun aChangeMadeWhileTheTokensRenewKeepsTheRenewedPairOnlyOnAnAccountStillWaiting() =
        serverTest(modules, withAudit(RecordingAuditLog())) { server ->
            with(server) {
                accountLines(
                    account("claude", "claude-1"),
                    account("claude", "claude-2"),
                    account("claude", "claude-3"),
                )
                reconnectRequired("claude-1", "claude-2", "claude-3")
                val shared = accounts()
                // While each pair renews: an admin renames claude-1, claude-2's owner reconnects it, claude-3 is
                // removed. Renewing rotated TeamCode's pair, so only claude-1, still waiting, can take the new one.
                (koin.get<OAuthRefresher>() as TeamCodeRefresher).meanwhile = { token ->
                    val id = token.removePrefix("access-").substringBefore("-token")
                    val account = shared.getValue(id).id
                    when (id) {
                        "claude-1" -> koin.get<UpdateSharedAccountUseCase>()(
                            adminA,
                            account,
                            UpdateAccountPayload(label = "Renamed"),
                        )
                        "claude-2" -> koin.get<ShareAccountUseCase>()(adminA, owners("claude-2"))
                        else -> koin.get<RemoveSharedAccountUseCase>()(adminA, account)
                    }
                }
                val changed = "The account changed meanwhile: nothing was overwritten. Try again."
                assertEquals(
                    listOf(
                        line("account", "claude/Lyes claude-1", UPDATED),
                        line("account", "claude/Lyes claude-2", SKIPPED, changed),
                        line("account", "claude/Lyes claude-3", SKIPPED, changed),
                    ),
                    accountLines(
                        account("claude", "claude-1", run = 2),
                        account("claude", "claude-2", run = 2),
                        account("claude", "claude-3", run = 2),
                        cutover = true,
                    ),
                )
                val now = accounts()
                assertEquals("Renamed", now.getValue("claude-1").label, "The admin's edit stays")
                assertEquals(AccountStatus.READY, now.getValue("claude-1").status)
                assertEquals("renewed-access-claude-1-token-2", accessToken("claude-1"), "The renewed pair is kept")
                assertEquals("access-owner-claude-2-token", accessToken("claude-2"), "The owner's reconnection stays")
                assertEquals(setOf("claude-1", "claude-2"), now.keys, "A removed account is never shared again")
            }
        }

    @Test
    fun accountsTheRunCannotReadAreSkippedAndWhatTheImportWroteStaysReported() {
        val unreadable = module {
            single<ListSharedAccountsUseCase> {
                object : ListSharedAccountsUseCase {
                    override suspend fun invoke(caller: Caller): List<SharedAccountView> =
                        throw OstromException(ErrorKind.UNAVAILABLE, "Unreadable.")
                }
            }
        }
        serverTest(modules, module { includes(importOverrides(), unreadable) }) { server ->
            with(server) {
                val report = report(Importing.team())
                assertEquals(
                    listOf(
                        line("account", "claude/Lyes", SKIPPED, "Unreadable."),
                        line("account", "codex/Lyes", SKIPPED, "Unreadable."),
                    ),
                    report.items.filter { it.kind == "account" },
                )
                assertEquals(setOf("grafana", "grafana.ro", "discord"), servers().keys, "Written before, still there")
            }
        }
    }

    /** claude-[id]'s owner signing in again. */
    private fun owners(id: String) = ShareAccountPayload(
        AccountProvider.CLAUDE,
        "Lyes $id",
        null,
        id,
        "access-owner-$id-token",
        "refresh-owner-$id-token",
        Instant.parse("2026-11-01T00:00:00Z"),
    )

    /** Records the audit entries it is given. */
    private class RecordingAuditLog : AuditLog {
        val entries = mutableListOf<AuditEntry>()

        override suspend fun record(entry: AuditEntry): AuditEvent {
            entries += entry
            val at = Instant.parse("2026-10-30T12:00:00Z")
            return AuditEvent(Uuid.random(), entries.size.toLong(), entry, at, null, "hash", "signature", "key")
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
