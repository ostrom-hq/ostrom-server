// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.sharing.infrastructure.SharingAccounts
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountOwnedByAnotherMemberException
import run.ostrom.server.sharing.domain.AccountRefreshRepository
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.OAuthRefresher
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.sharing.domain.RefreshWrite
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.presentation.SharedAccountResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL, two replicas of the tokens over one database: the refresh intent committed before the provider is
 * called, its owner, its handover to a share, and a dead owner's intent.
 */
class SharingRefreshPostgresTest : SharingPostgresHarness() {
    /** One replica's tokens over the shared database: its own refresher, the server's repository and cipher. */
    private fun TestServer.replica(refresher: OAuthRefresher) = AccountTokens(
        koin.get<AccountRefreshRepository>(),
        koin.get<AccountTokenCipher>(),
        refresher,
        Clock.System,
        JsonLogger(LogLevel.ERROR, sink = {}),
    )

    private suspend fun TestServer.storedAccount(id: String): SharedAccount =
        koin.get<SharedAccountRepository>().get(Sharing.orgA, Uuid.parse(id))!!

    /** Moves the account's refresh intent [ago] into the past, by the database's clock: its owner died. */
    private suspend fun TestServer.ageIntent(id: String, seconds: Int) {
        val account = Uuid.parse(id)
        koin.get<JdbcDatabase>().inOrg(Sharing.orgA) {
            SharingAccounts.update({ SharingAccounts.id eq account }) {
                it[refreshStartedAt] = databaseNowPlus((-seconds).seconds)
            }
        }
    }

    private suspend fun TestServer.intent(id: String): Pair<Boolean, Long> {
        val account = Uuid.parse(id)
        val row = koin.get<JdbcDatabase>().inOrg(Sharing.orgA) {
            SharingAccounts.select(SharingAccounts.refreshPending, SharingAccounts.credentialVersion)
                .where { SharingAccounts.id eq account }.single()
        }
        return row[SharingAccounts.refreshPending] to row[SharingAccounts.credentialVersion]
    }

    @Test
    fun aDecisionOnAStateThatChangedIsMadeAgainAndOneThatKeepsChangingFails() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val shared = shareAs("member-a").body<SharedAccountResponse>()
                val id = Uuid.parse(shared.id)
                val refreshes = koin.get<AccountRefreshRepository>()
                val accounts = koin.get<SharedAccountRepository>()
                // Another write lands between the read and the claim once: the claim is decided again, then lands.
                var decisions = 0
                val claimed = refreshes.locked(Sharing.orgA, id) { state ->
                    decisions++
                    if (decisions == 1) accounts.setStatus(Sharing.orgA, id, AccountStatus.DISABLED)
                    RefreshWrite.Claim to state?.pending
                }
                assertEquals(2, decisions)
                assertEquals(false, claimed)
                assertEquals(true, intent(shared.id).first)
                // A row rewritten at every decision: the look gives up.
                var round = 0
                assertFailsWith<IllegalStateException> {
                    refreshes.locked(Sharing.orgA, id) { _ ->
                        round++
                        val next = if (round % 2 == 0) AccountStatus.DISABLED else AccountStatus.READY
                        accounts.setStatus(Sharing.orgA, id, next)
                        RefreshWrite.Retire to Unit
                    }
                }
                assertEquals(8, round)
                // A gone account is decided on as such, and nothing is written.
                assertEquals(null, refreshes.locked(Sharing.orgA, Uuid.random()) { RefreshWrite.Claim to it })
            }
        }

    @Test
    fun twoReplicasLeasingTogetherSendTheRefreshTokenOnceAndBothServeTheRenewedPair() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val soon = (Clock.System.now() + 1.minutes).toString()
                val shared = shareAs("member-a", expiresAt = soon).body<SharedAccountResponse>()
                val account = storedAccount(shared.id)
                val renewed = AccountCredentials("renewed-access", "renewed-refresh", Clock.System.now() + 8.hours)
                val providers = List(2) {
                    FakeRefresher(RefreshResult.Renewed(renewed)).apply { onRefresh = { delay(500.milliseconds) } }
                }
                val replicas = providers.map { replica(it) }
                val served = coroutineScope {
                    replicas.map { tokens -> async { tokens.fresh(account) } }.awaitAll()
                }
                assertEquals(listOf("renewed-access", "renewed-access"), served.map { it?.accessToken })
                assertEquals(1, providers.sumOf { it.calls }, "One replica owns the refresh, the other waits for it")
                assertEquals(false to 2L, intent(shared.id), "The intent ended, the tokens' version moved once")
                assertEquals(AccountStatus.READY, storedAccount(shared.id).status)
            }
        }

    @Test
    fun aReplicaKilledDuringItsRefreshLeavesTheAccountToReconnectAndNoOtherReplicaSendsTheToken() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val now = Clock.System.now().toString()
                val shared = shareAs("member-a", expiresAt = now).body<SharedAccountResponse>()
                val account = storedAccount(shared.id)
                val refreshes = koin.get<AccountRefreshRepository>()
                // The killed replica committed its intent, then died before the provider answered.
                refreshes.locked(Sharing.orgA, account.id) { RefreshWrite.Claim to Unit }
                val other = FakeRefresher(RefreshResult.Renewed(AccountCredentials("x", "y", Clock.System.now())))
                ageIntent(shared.id, seconds = 61)
                assertNull(replica(other).fresh(account), "A dead owner's refresh is never taken over")
                assertEquals(0, other.calls)
                assertEquals(AccountStatus.RECONNECT_REQUIRED, storedAccount(shared.id).status)
                assertEquals(true to 1L, intent(shared.id), "The intent stays until new tokens come")

                assertEquals(HttpStatusCode.Created, shareAs("member-a").status, "Its owner shares it again")
                assertEquals(false to 2L, intent(shared.id), "The share clears the intent")
                assertFalse(refreshes.save(Sharing.orgA, account.id, 1, "stale-owner"), "Its old version is fenced")
                assertFalse(refreshes.fail(Sharing.orgA, account.id, 1, AccountStatus.WRONG_ACCOUNT))
                assertEquals(AccountStatus.READY, storedAccount(shared.id).status)
                assertEquals("sk-ant-oat01-secret-access", replica(other).fresh(account)?.accessToken)
            }
        }

    @Test
    fun newTokensWaitForARefreshInFlightAndAFailedRefreshKeepsItsIntent() = serverTest(modules, overrides) { server ->
        with(server) {
            val shared = shareAs("member-a", expiresAt = Clock.System.now().toString()).body<SharedAccountResponse>()
            val account = storedAccount(shared.id)
            val refreshes = koin.get<AccountRefreshRepository>()
            refreshes.locked(Sharing.orgA, account.id) { RefreshWrite.Claim to Unit }
            val refused = shareAs("member-a")
            assertEquals(HttpStatusCode.Conflict, refused.status, "A share never overwrites a refresh in flight")
            assertTrue("being renewed" in refused.bodyAsText(), refused.bodyAsText())
            assertEquals(true to 1L, intent(shared.id))
            // Another member is told the account is not theirs, not that it is being renewed.
            val repository = koin.get<SharedAccountRepository>()
            val cipher = koin.get<AccountTokenCipher>()
            val credentials = AccountCredentials("racer-access", "racer-refresh", Clock.System.now() + 8.hours)
            val stranger = account.copy(id = Uuid.random(), ownerMemberId = Sharing.otherA.memberId)
            assertFailsWith<AccountOwnedByAnotherMemberException> {
                repository.upsert(stranger, cipher.seal(credentials, Sharing.orgA, stranger.id))
            }

            assertTrue(refreshes.fail(Sharing.orgA, account.id, 1, AccountStatus.RECONNECT_REQUIRED))
            assertEquals(true to 1L, intent(shared.id), "A failed refresh keeps its intent")
            // A racing first share keeps the row's tokens, so it keeps their intent too.
            val racer = account.copy(id = Uuid.random(), status = AccountStatus.READY)
            assertEquals(account.id, repository.upsert(racer, cipher.seal(credentials, Sharing.orgA, racer.id)).id)
            assertEquals(true to 1L, intent(shared.id), "No new tokens stored, the intent stands")
            assertEquals(AccountStatus.RECONNECT_REQUIRED, storedAccount(shared.id).status)
            assertEquals(HttpStatusCode.Created, shareAs("member-a").status, "An account to reconnect takes new tokens")
            assertEquals(false to 2L, intent(shared.id))

            // A row that is gone is never written, whatever the look decides.
            val missing = Uuid.random()
            assertEquals(null, refreshes.locked(Sharing.orgA, missing) { RefreshWrite.Claim to it })
            assertNull(koin.get<SharedAccountRepository>().get(Sharing.orgA, missing))
        }
    }

    @Test
    fun anOtherReplicaStillRefreshingIsWaitedForUntilItsIntentGoesStale() = serverTest(modules, overrides) { server ->
        with(server) {
            val shared = shareAs("member-a", expiresAt = Clock.System.now().toString()).body<SharedAccountResponse>()
            val account = storedAccount(shared.id)
            koin.get<AccountRefreshRepository>().locked(Sharing.orgA, account.id) { RefreshWrite.Claim to Unit }
            ageIntent(shared.id, seconds = 59)
            val waiting = FakeRefresher()
            val served = coroutineScope {
                val look = async { replica(waiting).fresh(account) }
                delay(1.seconds)
                ageIntent(shared.id, seconds = 61)
                look.await()
            }
            assertNull(served)
            assertEquals(0, waiting.calls)
            assertEquals(AccountStatus.RECONNECT_REQUIRED, storedAccount(shared.id).status)
        }
    }
}
