// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing

import dev.ostrom.server.sharing.domain.AccountCredentials
import dev.ostrom.server.sharing.domain.AccountLimits
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountSpendReader
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.AccountTokenCipher
import dev.ostrom.server.sharing.domain.LeaseResult
import dev.ostrom.server.sharing.domain.NoAccountSpendReader
import dev.ostrom.server.sharing.domain.QuotaWindow
import dev.ostrom.server.sharing.domain.RefreshResult
import dev.ostrom.server.sharing.domain.SharedAccount
import dev.ostrom.server.sharing.domain.SharedAccountPool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SharedAccountPoolTest {
    private val repository = InMemoryAccounts()
    private val clock = TestClock()
    private val cipher = AccountTokenCipher(ContextCipher())
    private val refresher = FakeRefresher()
    private var spend: AccountSpendReader = NoAccountSpendReader()

    private fun pool() = pool(repository, cipher, refresher, spend, clock)

    private suspend fun account(
        label: String,
        priority: Int = 0,
        limits: AccountLimits = AccountLimits(),
        expiresAt: Instant = clock.now() + 1.days,
    ): SharedAccount {
        val org = Sharing.orgA
        val account =
            SharedAccount(
                Uuid.random(), org, AccountProvider.CLAUDE, label, null, "acct-$label", Sharing.memberA.memberId,
                priority, AccountStatus.READY, limits, null, clock.now(),
            )
        repository.accounts += account
        repository.envelopes[account.id] =
            cipher.seal(AccountCredentials("access-$label", "refresh-$label", expiresAt), org, account.id)
        return account
    }

    private suspend fun lease(
        session: String? = null,
        member: Uuid = Sharing.memberA.memberId,
        provider: AccountProvider = AccountProvider.CLAUDE,
    ) = pool().let { it.lease(Sharing.orgA, member, provider, session) }

    @Test
    fun theLeastUsedAccountWinsThenPriority() = runTest {
        val busy = account("busy", priority = 10)
        val idle = account("idle")
        repository.windows[busy.id] = mutableListOf(QuotaWindow("five_hour", 0.6, clock.now() + 1.hours, clock.now()))
        repository.windows[idle.id] = mutableListOf(QuotaWindow("five_hour", 0.1, clock.now() + 1.hours, clock.now()))
        val leased = assertIs<LeaseResult.Leased>(lease()).lease
        assertEquals("idle", leased.account.label)
        assertEquals("access-idle", leased.credentials.accessToken)
        repository.windows.clear()
        assertEquals("busy", assertIs<LeaseResult.Leased>(lease()).lease.account.label, "Equal load: priority")
    }

    @Test
    fun aSessionSticksToItsAccount() = runTest {
        account("a", priority = 1)
        account("b")
        val pool = pool()
        val first = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, "s1"),
        ).lease
        first.release()
        val b = repository.accounts.indexOfFirst { it.label == "b" }
        repository.accounts[b] = repository.accounts[b].copy(priority = 5)
        val again = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, "s1"),
        ).lease
        assertEquals(first.account.id, again.account.id)
        clock.advance(2.hours)
        val later = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, "s1"),
        ).lease
        assertEquals("b", later.account.label, "Affinity expires")
    }

    @Test
    fun accountsAtTheirLimitsAreSkipped() = runTest {
        val full = account("full", priority = 9, limits = AccountLimits(utilizationCeiling = 0.8))
        val cooling = account("cooling", priority = 8)
        val broke = account("broke", priority = 7, limits = AccountLimits(monthlyBudgetMicros = 1_000))
        val private =
            account("private", priority = 6, limits = AccountLimits(allowedMembers = setOf(Sharing.otherA.memberId)))
        val ok = account("ok")
        repository.windows[full.id] = mutableListOf(
            QuotaWindow("seven_day", 0.85, clock.now() + 2.days, clock.now()),
            QuotaWindow("model:opus", 1.0, clock.now() + 2.days, clock.now()),
        )
        repository.setCooldown(Sharing.orgA, cooling.id, clock.now() + 1.minutes)
        spend = AccountSpendReader { id, _ -> if (id == broke.id) 1_000 else 0 }
        assertEquals(ok.id, assertIs<LeaseResult.Leased>(lease()).lease.account.id)
        assertEquals(private.id, assertIs<LeaseResult.Leased>(lease(member = Sharing.otherA.memberId)).lease.account.id)
    }

    @Test
    fun aStaleWindowNoLongerCounts() = runTest {
        val account = account("a", limits = AccountLimits(utilizationCeiling = 0.5))
        repository.windows[account.id] =
            mutableListOf(QuotaWindow("five_hour", 0.9, clock.now() + 1.minutes, clock.now()))
        assertIs<LeaseResult.Refused>(lease())
        clock.advance(2.minutes)
        assertIs<LeaseResult.Leased>(lease())
    }

    @Test
    fun refusalsSayWhyAndWhen() = runTest {
        val none = assertIs<LeaseResult.Refused>(lease()).refusal
        assertTrue("No shared claude account" in none.reason)
        assertNull(none.retryAt)
        val cooling = account("cooling")
        repository.setCooldown(Sharing.orgA, cooling.id, clock.now() + 30.seconds)
        val full = account("full")
        repository.windows[full.id] =
            mutableListOf(QuotaWindow("five_hour", 1.0, clock.now() + 10.seconds, clock.now()))
        val atLimit = assertIs<LeaseResult.Refused>(lease()).refusal
        assertTrue("at its limit" in atLimit.reason)
        assertEquals(clock.now() + 10.seconds, atLimit.retryAt)
        assertIs<LeaseResult.Refused>(lease(provider = AccountProvider.CODEX))
    }

    @Test
    fun concurrencyIsBoundedAndReleased() = runTest {
        account("only", limits = AccountLimits(maxConcurrent = 1))
        val pool = pool()
        val first = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        ).lease
        assertEquals(1, pool.inFlight(first.account.id))
        val busy = assertIs<LeaseResult.Refused>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        ).refusal
        assertTrue("busy" in busy.reason)
        assertEquals(clock.now() + 5.seconds, busy.retryAt)
        first.release()
        first.release()
        assertEquals(0, pool.inFlight(first.account.id))
        assertIs<LeaseResult.Leased>(pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null))
    }

    @Test
    fun tokensCloseToExpiryAreRefreshedAndSealedAgain() = runTest {
        val account = account("a", expiresAt = clock.now() + 1.minutes)
        refresher.result = RefreshResult.Renewed(AccountCredentials("access-new", "refresh-new", clock.now() + 8.hours))
        val leased = assertIs<LeaseResult.Leased>(lease()).lease
        assertEquals("access-new", leased.credentials.accessToken)
        assertEquals(
            "access-new",
            cipher.open(repository.envelopes.getValue(account.id), Sharing.orgA, account.id)!!.accessToken,
        )
        leased.release()
        lease()
        assertEquals(1, refresher.calls, "Fresh tokens are not refreshed again")
    }

    @Test
    fun aRevokedRefreshTokenRetiresTheAccountAndTheNextOneServes() = runTest {
        val revoked = account("revoked", priority = 5, expiresAt = clock.now())
        account("backup")
        refresher.result = RefreshResult.Revoked
        assertEquals("backup", assertIs<LeaseResult.Leased>(lease()).lease.account.label)
        assertEquals(AccountStatus.RECONNECT_REQUIRED, repository.get(Sharing.orgA, revoked.id)!!.status)
    }

    @Test
    fun anUnreachableProviderKeepsTokensThatStillWork() = runTest {
        account("soon", expiresAt = clock.now() + 2.minutes)
        refresher.result = RefreshResult.Unavailable
        assertEquals("access-soon", assertIs<LeaseResult.Leased>(lease()).lease.credentials.accessToken)
        repository.accounts.clear()
        account("expired", expiresAt = clock.now() - 1.minutes)
        assertIs<LeaseResult.Refused>(lease(), "Tried once, never loops")
        assertEquals(AccountStatus.READY, repository.accounts.single().status)
    }

    @Test
    fun anEnvelopeThatNoLongerOpensRetiresTheAccount() = runTest {
        val broken = account("broken")
        repository.envelopes[broken.id] = "garbage"
        assertIs<LeaseResult.Refused>(lease())
        assertEquals(AccountStatus.RECONNECT_REQUIRED, repository.accounts.single().status)
        repository.accounts.clear()
        val gone = account("gone")
        repository.envelopes.remove(gone.id)
        assertIs<LeaseResult.Refused>(lease())
    }

    @Test
    fun observationsRecordWindowsCoolDownsAndForcedRefresh() = runTest {
        val account = account("a")
        val leased = assertIs<LeaseResult.Leased>(lease()).lease
        val reset = clock.now().epochSeconds + 3600
        leased.observe(
            200,
            mapOf(
                "anthropic-ratelimit-unified-5h-utilization" to "0.42",
                "anthropic-ratelimit-unified-5h-reset" to "$reset",
            ),
        )
        assertEquals(0.42, repository.windows.getValue(account.id).single().utilization)
        leased.observe(429, mapOf("retry-after" to "30"))
        assertEquals(clock.now() + 30.seconds, repository.get(Sharing.orgA, account.id)!!.cooldownUntil)
        leased.observe(
            429,
            mapOf(
                "anthropic-ratelimit-unified-7d-status" to "rejected",
                "anthropic-ratelimit-unified-7d-reset" to "$reset",
            ),
        )
        assertEquals(Instant.fromEpochSeconds(reset), repository.get(Sharing.orgA, account.id)!!.cooldownUntil)
        leased.observe(429, emptyMap())
        assertEquals(
            clock.now() + SharedAccountPool.DEFAULT_COOLDOWN,
            repository.get(Sharing.orgA, account.id)!!.cooldownUntil,
        )
        leased.observe(401, emptyMap())
        assertEquals(
            Instant.fromEpochMilliseconds(0),
            cipher.open(repository.envelopes.getValue(account.id), Sharing.orgA, account.id)!!.expiresAt,
        )
        repository.envelopes[account.id] = "garbage"
        leased.observe(401, emptyMap())
        repository.envelopes.remove(account.id)
        leased.observe(401, emptyMap())
    }

    @Test
    fun theMonthStartsOnTheFirstAtMidnightUtc() {
        assertEquals(
            Instant.parse("2026-10-01T00:00:00Z"),
            SharedAccountPool.monthStart(Instant.parse("2026-10-30T23:59:59Z")),
        )
        assertEquals(
            Instant.parse("2024-02-01T00:00:00Z"),
            SharedAccountPool.monthStart(Instant.parse("2024-02-29T10:00:00Z")),
        )
        assertEquals(
            Instant.parse("2027-01-01T00:00:00Z"),
            SharedAccountPool.monthStart(Instant.parse("2027-01-01T00:00:00Z")),
        )
        assertEquals(
            Instant.parse("1969-12-01T00:00:00Z"),
            SharedAccountPool.monthStart(Instant.parse("1969-12-31T12:00:00Z")),
        )
    }

    @Test
    fun anAccountUnderItsBudgetAndAFullWindowWithoutResetAreWeighed() = runTest {
        val unknownReset = account("unknown-reset", priority = 9)
        repository.windows[unknownReset.id] = mutableListOf(QuotaWindow("five_hour", 1.0, null, clock.now()))
        val budgeted = account("budgeted", limits = AccountLimits(monthlyBudgetMicros = 5_000))
        spend = AccountSpendReader { _, _ -> 10 }
        assertEquals(budgeted.id, assertIs<LeaseResult.Leased>(lease()).lease.account.id)
        repository.accounts.removeAll { it.id == budgeted.id }
        val refusal = assertIs<LeaseResult.Refused>(lease()).refusal
        assertNull(refusal.retryAt, "A full window that names no reset gives no retry time")
    }

    @Test
    fun aSlotIsGivenBackWhenTheTokensCannotBeRead() = runTest {
        val account = account("a")
        val pool = pool()
        repository.failing = true
        assertFailsWith<IllegalStateException> {
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null)
        }
        assertEquals(0, pool.inFlight(account.id), "The failed lease holds no slot")
    }

    @Test
    fun slotsAreCountedPerAccount() = runTest {
        val account = account("shared", limits = AccountLimits(maxConcurrent = 2))
        val pool = pool()
        val first = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        ).lease
        assertIs<LeaseResult.Leased>(pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null))
        first.release()
        assertEquals(1, pool.inFlight(account.id))
        pool.release(Uuid.random())
        assertEquals(1, pool.inFlight(account.id), "Releasing an account never leased changes nothing")
    }

    @Test
    fun expiredSessionsAreForgottenOnceTooManyAreRemembered() = runTest {
        account("a")
        val pool = pool()
        repeat(SharedAccountPool.MAX_AFFINITY) {
            assertIs<LeaseResult.Leased>(
                pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, "s$it"),
            ).lease.release()
        }
        clock.advance(2.hours)
        val last = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, "last"),
        ).lease
        assertEquals("a", last.account.label)
    }

    @Test
    fun aRetryAfterOutOfRangeOrUnreadableIsIgnored() = runTest {
        val account = account("a")
        val leased = assertIs<LeaseResult.Leased>(lease()).lease
        for (value in listOf("0", "100000", "soon")) {
            leased.observe(429, mapOf("retry-after" to value))
            assertEquals(
                clock.now() + SharedAccountPool.DEFAULT_COOLDOWN,
                repository.get(Sharing.orgA, account.id)!!.cooldownUntil,
                value,
            )
        }
    }
}
