// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.ModelCallRecord
import run.ostrom.server.gateway.domain.ModelUsageSink
import run.ostrom.server.gateway.domain.WireProtocol
import run.ostrom.server.kernel.sharing.PoolSpend
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.MemberPoolRepository
import run.ostrom.server.sharing.domain.PoolBudget
import run.ostrom.server.sharing.domain.PoolBudgetRepository
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * TeamCode's « historical reservations retain their original pool » on real PostgreSQL, the usage and sharing modules
 * wired as the server wires them: a call served by a pooled account is recorded with the pool it was served from, and
 * what a shared overflow pool lent today stays on it when the account that served it moves to another pool, so that
 * the pool's daily budget keeps binding (TeamCode's `DailyScope.LENT`, keyed by the reservation's source pool).
 */
class PoolSpendPostgresTest {
    private val clock = TestClock()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }
    private val modules = listOf(SecretsModule, UsageModule, SharingModule)
    private val org = TestCallers.orgA
    private val member = TestCallers.memberA.memberId

    /** A Claude account of [poolId], its tokens sealed as sharing seals them. */
    private suspend fun TestServer.account(label: String, poolId: String): SharedAccount {
        val account = SharedAccount(
            Uuid.random(), org, AccountProvider.CLAUDE, label, null, "acct-$label", null, 0, AccountStatus.READY,
            AccountLimits(), null, clock.now(),
        )
        val credentials = AccountCredentials("access-$label", "refresh-$label", clock.now() + 1.days)
        val accounts = koin.get<SharedAccountRepository>()
        val stored = accounts.upsert(account, koin.get<AccountTokenCipher>().seal(credentials, org, account.id))
        return assertNotNull(accounts.update(stored.copy(poolId = poolId)))
    }

    /** The account the member leases for a call priced at [estimate], or null when refused. */
    private suspend fun TestServer.leased(estimate: Long): SharedAccount? {
        val result = koin.get<SharedAccountPool>().lease(org, member, AccountProvider.CLAUDE, null) { estimate }
        return when (result) {
            is LeaseResult.Leased -> result.lease.account.also { result.lease.release() }
            is LeaseResult.Refused -> null
        }
    }

    /** What the gateway records once [account] served a call that cost [micros]. */
    private suspend fun TestServer.served(account: SharedAccount, micros: Long) =
        koin.get<ModelUsageSink>().record(
            ModelCallRecord(
                Uuid.random(), org, member, null, WireProtocol.MESSAGES, InferenceProvider.CLAUDE_SHARED, account.id,
                account.poolId, "claude-opus-5", null, micros, true, 200, "ok", clock.now(), 10, 100,
            ),
        )

    @Test
    fun anAccountMovedOutOfAReserveLeavesTheDaysSpendOnItAndItsBudgetStillBinds() =
        serverTest(modules, overrides) { server ->
            with(server) {
                koin.get<PoolBudgetRepository>().save(org, PoolBudget("reserve", 1_000, true))
                koin.get<MemberPoolRepository>().setPolicy(org, member, null, true)
                val lent = account("lent", "reserve")
                assertEquals(lent.id, leased(1_000)?.id, "A member in no pool borrows the reserve")
                served(lent, 900)

                // The account that lent moves to another pool; another one joins the reserve.
                val moved = assertNotNull(koin.get<SharedAccountRepository>().update(lent.copy(poolId = "us")))
                val fresh = account("fresh", "reserve")
                val spend = koin.get<PoolSpend>()
                val today = clock.now() - 1.days
                assertEquals(mapOf("reserve" to 900L), spend.spentMicros(org, listOf("reserve", "us"), today))
                assertEquals(fresh.id, leased(100)?.id, "1,000 - 900 still covers 100, on the account still in it")
                assertEquals(null, leased(101), "Not 101: the moved account's calls still count on the reserve")

                // Its next call is the other pool's.
                served(moved, 50)
                assertEquals(
                    mapOf("reserve" to 900L, "us" to 50L),
                    spend.spentMicros(org, listOf("reserve", "us"), today),
                )
            }
        }
}
