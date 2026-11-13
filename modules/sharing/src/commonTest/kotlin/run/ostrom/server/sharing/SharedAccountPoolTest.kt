// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.AccountSpendReader
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.domain.UNKNOWN_RESET
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private val logged = mutableListOf<String>()

    private fun pool() = pool(repository, tokens(repository, cipher, refresher, clock, logged::add), spend, clock)

    private suspend fun account(
        label: String,
        priority: Int = 0,
        limits: AccountLimits = AccountLimits(),
        expiresAt: Instant = clock.now() + 1.days,
        provider: AccountProvider = AccountProvider.CLAUDE,
    ): SharedAccount {
        val org = Sharing.orgA
        val account =
            SharedAccount(
                Uuid.random(), org, provider, label, null, "acct-$label", Sharing.memberA.memberId,
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
        clock.advance(AccountSlotRepository.AFFINITY + 1.minutes)
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
        assertEquals(1, pool.inFlight(Sharing.orgA)[first.account.id] ?: 0)
        val busy = assertIs<LeaseResult.Refused>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        ).refusal
        assertTrue("busy" in busy.reason)
        assertEquals(clock.now() + SharedAccountPool.BUSY_RETRY, busy.retryAt)
        first.release()
        first.release()
        assertEquals(0, pool.inFlight(Sharing.orgA)[first.account.id] ?: 0)
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
    fun aClaudeRefreshNamingAnotherAccountTakesItOutOfServiceAndTheNextOneServes() = runTest {
        foreignTokensAreRefused(AccountProvider.CLAUDE)
    }

    @Test
    fun aCodexRefreshNamingAnotherAccountTakesItOutOfServiceAndTheNextOneServes() = runTest {
        foreignTokensAreRefused(AccountProvider.CODEX)
    }

    private suspend fun foreignTokensAreRefused(provider: AccountProvider) {
        val foreign = account("foreign", priority = 5, expiresAt = clock.now(), provider = provider)
        account("backup", provider = provider)
        refresher.result = RefreshResult.Renewed(
            AccountCredentials("access-new", "refresh-new", clock.now() + 8.hours),
            accountId = "acct-someone-else",
        )
        assertEquals("backup", assertIs<LeaseResult.Leased>(lease(provider = provider)).lease.account.label)
        assertEquals(AccountStatus.WRONG_ACCOUNT, repository.get(Sharing.orgA, foreign.id)!!.status)
        val kept = cipher.open(repository.envelopes.getValue(foreign.id), Sharing.orgA, foreign.id)!!
        assertEquals("access-foreign", kept.accessToken, "The other account's tokens are not stored")
        assertEquals(1, refresher.calls)
        assertEquals("backup", assertIs<LeaseResult.Leased>(lease(provider = provider)).lease.account.label)
        assertEquals(1, refresher.calls, "An account out of service is not refreshed again")
        val warning = logged.single()
        assertTrue(foreign.id.toString() in warning && provider.wire in warning, warning)
        assertFalse("access-new" in warning || "refresh-new" in warning, "No token is logged")
    }

    @Test
    fun anAccountSharedAgainDuringAForeignRefreshKeepsItsNewTokens() = runTest {
        val account = account("raced", expiresAt = clock.now())
        refresher.result = RefreshResult.Renewed(
            AccountCredentials("access-foreign", "refresh-foreign", clock.now() + 8.hours),
            accountId = "acct-someone-else",
        )
        refresher.onRefresh = { shareAgain(account, "owner") }
        assertEquals("access-owner", assertIs<LeaseResult.Leased>(lease()).lease.credentials.accessToken)
        assertEquals(AccountStatus.READY, repository.get(Sharing.orgA, account.id)!!.status, "Its share stands")
        assertEquals(1, refresher.calls, "The new tokens are fresh: not refreshed again")
        assertTrue(logged.isEmpty(), "No warning for an account whose share stands")
    }

    @Test
    fun anAccountSharedAgainDuringARenewalKeepsItsNewTokensAndDropsTheRenewedPair() = runTest {
        val account = account("raced", expiresAt = clock.now())
        refresher.result = RefreshResult.Renewed(AccountCredentials("access-new", "refresh-new", clock.now() + 8.hours))
        refresher.onRefresh = { shareAgain(account, "owner") }
        assertEquals("access-owner", assertIs<LeaseResult.Leased>(lease()).lease.credentials.accessToken)
        assertEquals(
            "access-owner",
            cipher.open(repository.envelopes.getValue(account.id), Sharing.orgA, account.id)!!.accessToken,
        )
    }

    /** The owner shares [account] again with fresh tokens named after [label], as the CLI does. */
    private suspend fun shareAgain(account: SharedAccount, label: String) {
        val tokens = AccountCredentials("access-$label", "refresh-$label", clock.now() + 8.hours)
        repository.upsert(account, cipher.seal(tokens, Sharing.orgA, account.id))
    }

    @Test
    fun aRefreshNamingNoAccountOrTheDeclaredOneServes() = runTest {
        val silent = account("silent", expiresAt = clock.now())
        refresher.result = RefreshResult.Renewed(AccountCredentials("access-new", "refresh-new", clock.now() + 8.hours))
        assertEquals("access-new", assertIs<LeaseResult.Leased>(lease()).lease.credentials.accessToken)
        assertEquals(AccountStatus.READY, repository.get(Sharing.orgA, silent.id)!!.status, "No identity, no refusal")
        repository.accounts.clear()
        val named = account("named", expiresAt = clock.now())
        refresher.result = RefreshResult.Renewed(
            AccountCredentials("access-named", "refresh-named", clock.now() + 8.hours),
            accountId = " ACCT-NAMED ",
        )
        assertEquals("access-named", assertIs<LeaseResult.Leased>(lease()).lease.credentials.accessToken)
        assertEquals(AccountStatus.READY, repository.get(Sharing.orgA, named.id)!!.status, "Case and spaces aside")
    }

    @Test
    fun anUnreachableProviderRetiresTheAccountSinceItsRotationIsUncertain() = runTest {
        val unsure = account("unsure", priority = 5, expiresAt = clock.now() + 2.minutes)
        account("backup")
        refresher.result = RefreshResult.Unavailable
        assertEquals("backup", assertIs<LeaseResult.Leased>(lease()).lease.account.label)
        assertEquals(AccountStatus.RECONNECT_REQUIRED, repository.get(Sharing.orgA, unsure.id)!!.status)
        assertTrue(unsure.id in repository.pending, "The intent stays until new tokens clear it")
        lease()
        assertEquals(1, refresher.calls, "Its refresh token is never sent again")
    }

    @Test
    fun aRefreshPastItsTimeoutRetiresTheAccount() = runTest {
        val slow = account("slow", priority = 5, expiresAt = clock.now())
        account("backup")
        refresher.result = RefreshResult.Renewed(AccountCredentials("access-new", "refresh-new", clock.now() + 8.hours))
        refresher.onRefresh = { delay(AccountTokens.REFRESH_TIMEOUT + 1.seconds) }
        assertEquals("backup", assertIs<LeaseResult.Leased>(lease()).lease.account.label)
        assertEquals(AccountStatus.RECONNECT_REQUIRED, repository.get(Sharing.orgA, slow.id)!!.status)
    }

    @Test
    fun tokensAreRenewedOnlyWithinTheirMarginOfExpiry() = runTest {
        account("a", expiresAt = clock.now() + AccountTokens.REFRESH_MARGIN + 1.seconds)
        refresher.result = RefreshResult.Renewed(AccountCredentials("access-new", "refresh-new", clock.now() + 8.hours))
        assertEquals("access-a", assertIs<LeaseResult.Leased>(lease()).lease.credentials.accessToken)
        assertEquals(0, refresher.calls, "Still outside the margin: served as they are")
        clock.advance(2.seconds)
        val renewed = assertIs<LeaseResult.Leased>(lease()).lease
        assertEquals("access-new", renewed.credentials.accessToken)
        assertEquals(1, refresher.calls)
    }

    @Test
    fun aRefreshThatThrowsRetiresTheAccountAndKeepsItsIntent() = runTest {
        val broken = account("broken", priority = 5, expiresAt = clock.now())
        account("backup")
        refresher.onRefresh = { throw IllegalStateException("The provider's answer could not be read") }
        assertEquals("backup", assertIs<LeaseResult.Leased>(lease()).lease.account.label)
        assertEquals(AccountStatus.RECONNECT_REQUIRED, repository.get(Sharing.orgA, broken.id)!!.status)
        assertTrue(broken.id in repository.pending, "The intent stays until new tokens clear it")
    }

    @Test
    fun aCancellationThrownByTheRefresherIsNotSwallowed() = runTest {
        account("a", expiresAt = clock.now())
        refresher.onRefresh = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { lease() }
    }

    @Test
    fun aRequestCancelledDuringARefreshLetsTheRefreshEndAndBeStored() = runTest {
        val account = account("a", expiresAt = clock.now())
        refresher.result = RefreshResult.Renewed(AccountCredentials("access-new", "refresh-new", clock.now() + 8.hours))
        refresher.onRefresh = { delay(1.seconds) }
        val request = launch { lease() }
        testScheduler.runCurrent()
        request.cancelAndJoin()
        assertEquals(
            "access-new",
            cipher.open(repository.envelopes.getValue(account.id), Sharing.orgA, account.id)!!.accessToken,
            "The rotated pair is kept, not lost with the request",
        )
        assertEquals(AccountStatus.READY, repository.get(Sharing.orgA, account.id)!!.status)
        assertFalse(account.id in repository.pending)
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
    fun aStallRestsTheAccountAMinuteButNeverShortensALongerCoolDown() = runTest {
        val account = account("a")
        val leased = assertIs<LeaseResult.Leased>(lease()).lease
        fun cooldown() = repository.accounts.singleOrNull()?.cooldownUntil
        leased.stalled()
        assertEquals(clock.now() + SharedAccountPool.STALL_COOLDOWN, cooldown(), "A provider that stalled rests it")
        leased.stalled()
        assertEquals(clock.now() + SharedAccountPool.STALL_COOLDOWN, cooldown())
        leased.observe(429, mapOf("retry-after" to "3600"))
        leased.stalled()
        assertEquals(clock.now() + 3600.seconds, cooldown(), "A 429's longer cool-down stays")
        repository.accounts.clear()
        leased.stalled()
        assertNull(cooldown(), "An account removed meanwhile has nothing to rest")
        assertEquals(account.id, leased.account.id)
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
        val replaced = rotated(account)
        repository.envelopes[account.id] = replaced
        leased.observe(401, emptyMap())
        assertEquals(replaced, repository.envelopes[account.id], "Tokens a refresh already replaced are kept")
        repository.envelopes[account.id] = leasedTokens(account)
        repository.pending += account.id
        leased.observe(401, emptyMap())
        assertEquals(leasedTokens(account), repository.envelopes[account.id], "A refresh in progress is left alone")
        repository.pending -= account.id
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
        for ((start, instant) in listOf(
            "1969-12-01T00:00:00Z" to "1969-12-01T00:00:00Z",
            "-0400-03-01T00:00:00Z" to "-0400-03-01T00:00:00Z",
            "-0001-06-01T00:00:00Z" to "-0001-06-15T10:00:00Z",
        )) {
            assertEquals(Instant.parse(start), SharedAccountPool.monthStart(Instant.parse(instant)), instant)
        }
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
        assertEquals(clock.now() + UNKNOWN_RESET, refusal.retryAt, "A full window without a reset is believed 5 min")
        clock.advance(UNKNOWN_RESET)
        assertEquals(
            unknownReset.id,
            assertIs<LeaseResult.Leased>(lease()).lease.account.id,
            "Then the account is tried again: a missing reset header never closes it for good",
        )
    }

    @Test
    fun anotherReplicaRefreshingIsWaitedForAndItsTokensUsedWithoutSendingTheTokenAgain() = runTest {
        val account = account("a", expiresAt = clock.now())
        repository.pending += account.id
        val other = launch {
            delay(2.seconds)
            // The other replica stores its rotated pair and ends its intent.
            repository.save(Sharing.orgA, account.id, 1, rotated(account))
        }
        assertEquals("access-other", assertIs<LeaseResult.Leased>(lease()).lease.credentials.accessToken)
        other.join()
        assertEquals(0, refresher.calls, "Only the owner of the intent calls the provider")
        assertEquals(AccountStatus.READY, repository.accounts.single().status)
    }

    @Test
    fun aRefreshThatNeverEndsElsewhereGivesUpOnTheAccountForThisLeaseOnly() = runTest {
        val busy = account("busy", priority = 5, expiresAt = clock.now())
        account("backup")
        repository.pending += busy.id
        val start = testScheduler.currentTime
        assertEquals("backup", assertIs<LeaseResult.Leased>(lease()).lease.account.label)
        assertEquals(
            AccountTokens.WAIT_TURN.inWholeMilliseconds * (AccountTokens.WAIT_TURNS - 1),
            testScheduler.currentTime - start,
            "It waited for the owner, 400 looks",
        )
        assertEquals(AccountStatus.READY, repository.get(Sharing.orgA, busy.id)!!.status, "Not retired: not stale yet")
        assertEquals(0, refresher.calls)
    }

    @Test
    fun aStaleIntentRetiresTheAccountWithoutSendingTheToken() = runTest {
        val dead = account("dead", priority = 5, expiresAt = clock.now())
        account("backup")
        repository.pending += dead.id
        repository.staleIntents += dead.id
        assertEquals("backup", assertIs<LeaseResult.Leased>(lease()).lease.account.label)
        assertEquals(AccountStatus.RECONNECT_REQUIRED, repository.get(Sharing.orgA, dead.id)!!.status)
        assertEquals(0, refresher.calls, "A dead owner's refresh is never taken over")
        shareAgain(dead, "owner")
        assertFalse(dead.id in repository.pending, "New tokens clear the intent")
    }

    @Test
    fun anAccountNoLongerReadyWhenItsTokensAreReadServesNothing() = runTest {
        val account = account("a")
        val pool = pool()
        repository.accounts[0] = account.copy(status = AccountStatus.DISABLED)
        assertNull(tokens(repository, cipher, refresher, clock).fresh(account))
        assertEquals(AccountStatus.DISABLED, repository.accounts.single().status, "Left as it is")
        assertIs<LeaseResult.Refused>(pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null))
    }

    // The sealing is deterministic (ContextCipher): the same tokens seal to the same envelope.
    private suspend fun leasedTokens(account: SharedAccount) = cipher.seal(
        AccountCredentials("access-${account.label}", "refresh-${account.label}", clock.now() + 1.days),
        Sharing.orgA,
        account.id,
    )

    private suspend fun rotated(account: SharedAccount) = cipher.seal(
        AccountCredentials("access-other", "refresh-other", clock.now() + 8.hours),
        Sharing.orgA,
        account.id,
    )

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

    @Test
    fun whenEveryOpenAccountIsBusyTheRetryIsTheFirstReopening() = runTest {
        val closed = account("closed", priority = 5)
        repository.setCooldown(Sharing.orgA, closed.id, clock.now() + 20.seconds)
        account("open", limits = AccountLimits(maxConcurrent = 1))
        val pool = pool()
        assertIs<LeaseResult.Leased>(pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null))
        val busy = assertIs<LeaseResult.Refused>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        ).refusal
        assertTrue("busy" in busy.reason)
        assertEquals(clock.now() + 20.seconds, busy.retryAt)
    }

    @Test
    fun aPastCoolDownNoLongerHoldsTheAccountBack() = runTest {
        val account = account("a")
        repository.setCooldown(Sharing.orgA, account.id, clock.now() - 1.minutes)
        assertEquals(account.id, assertIs<LeaseResult.Leased>(lease()).lease.account.id)
    }

    @Test
    fun theLoadOfAnAccountIsItsMostUsedWindow() = runTest {
        val heavy = account("heavy", priority = 9)
        val light = account("light")
        val reset = clock.now() + 1.hours
        repository.windows[heavy.id] = mutableListOf(
            QuotaWindow("five_hour", 0.1, reset, clock.now()),
            QuotaWindow("seven_day", 0.6, reset, clock.now()),
            QuotaWindow("model:opus", 0.0, reset, clock.now()),
        )
        repository.windows[light.id] = mutableListOf(
            QuotaWindow("seven_day", 0.3, reset, clock.now()),
            QuotaWindow("five_hour", 0.2, reset, clock.now()),
        )
        assertEquals(light.id, assertIs<LeaseResult.Leased>(lease()).lease.account.id)
    }

    @Test
    fun aRefusalUnderTheFullMarkCoolsDownForItsRetryAfter() = runTest {
        val account = account("a")
        val leased = assertIs<LeaseResult.Leased>(lease()).lease
        leased.observe(429, mapOf("anthropic-ratelimit-unified-5h-utilization" to "0.5", "retry-after" to "30"))
        assertEquals(clock.now() + 30.seconds, repository.get(Sharing.orgA, account.id)!!.cooldownUntil)
    }
}
