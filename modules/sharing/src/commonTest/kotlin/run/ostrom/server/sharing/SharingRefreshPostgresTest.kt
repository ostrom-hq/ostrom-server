// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing

import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
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
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
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
        driver.executeInOrg(
            Sharing.orgA,
            Statement.create(
                "UPDATE sharing_accounts SET refresh_started_at = now() - :seconds * INTERVAL '1 second' " +
                    "WHERE id = :id",
            ).bind("seconds", seconds).bind("id", Uuid.parse(id)),
        ).getOrThrow()
    }

    private suspend fun TestServer.intent(id: String): Pair<Boolean, Long> = driver.fetchInOrg(
        Sharing.orgA,
        Statement.create("SELECT refresh_pending, credential_version FROM sharing_accounts WHERE id = :id")
            .bind("id", Uuid.parse(id)),
    ).getOrThrow().single().let { it.get("refresh_pending").asBoolean() to it.get("credential_version").asLong() }

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
