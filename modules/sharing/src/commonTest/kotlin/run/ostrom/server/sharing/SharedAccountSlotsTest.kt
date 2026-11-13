// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountSlot
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.AccountSpendReader
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SlotView
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The pool's slots: concurrency per account, their lease and renewal, and the sessions' accounts in their bounds. */
class SharedAccountSlotsTest {
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

    @Test
    fun aLeaseIsRefusedWhileEveryAccountIsAtItsConcurrency() = runTest {
        account("only", limits = AccountLimits(maxConcurrent = 1))
        val pool = pool()
        val first = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, "s"),
        ).lease
        assertIs<LeaseResult.Refused>(pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, "s"))
        first.release()
        assertIs<LeaseResult.Leased>(pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null))
    }

    @Test
    fun aReleaseDuringCancellationStillGivesTheSlotBack() = runTest {
        // The release runs in the `finally` of a cancelled request: it must not be cut short there.
        val account = account("a", limits = AccountLimits(maxConcurrent = 1))
        val pool = pool()
        val leased = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        ).lease
        val job = launch {
            try {
                awaitCancellation()
            } finally {
                leased.release()
            }
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(0, pool.inFlight(Sharing.orgA)[account.id] ?: 0)
    }

    @Test
    fun aSlotIsGivenBackWhenTheTokensCannotBeRead() = runTest {
        val account = account("a")
        val pool = pool()
        repository.failing = true
        assertFailsWith<IllegalStateException> {
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null)
        }
        assertEquals(0, pool.inFlight(Sharing.orgA)[account.id] ?: 0, "The failed lease holds no slot")
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
        assertEquals(1, pool.inFlight(Sharing.orgA)[account.id] ?: 0)
        pool.release(Sharing.orgA, AccountSlot(Uuid.random(), account.id))
        assertEquals(1, pool.inFlight(Sharing.orgA)[account.id] ?: 0, "Releasing a slot never taken changes nothing")
        clock.advance(AccountSlotRepository.SLOT_LEASE)
        assertEquals(null, pool.inFlight(Sharing.orgA)[account.id], "A slot never given back frees itself")
    }

    @Test
    fun aLongRequestRenewsItsSlotWhileItRunsAndOnlyThen() = runTest {
        val account = account("long")
        val pool = pool()
        val lease = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        ).lease
        val counted = lease.held {
            repeat(4) {
                delay(AccountSlotRepository.SLOT_RENEWAL)
                clock.advance(AccountSlotRepository.SLOT_RENEWAL)
            }
            pool.inFlight(Sharing.orgA)[account.id]
        }
        assertEquals(1, counted, "Twenty minutes in, past the slot's lease, the stream still counts")
        clock.advance(AccountSlotRepository.SLOT_LEASE)
        assertEquals(null, pool.inFlight(Sharing.orgA)[account.id], "Once the request ends, nothing renews it")
    }

    @Test
    fun aSlotTheDatabaseCannotRenewOrTakeBackFailsNoRequest() = runTest {
        account("a")
        val slots = InMemorySlots(clock, repository)
        val pool = pool(repository, tokens(repository, cipher, refresher, clock), spend, clock, slots)
        val lease = assertIs<LeaseResult.Leased>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        ).lease
        slots.failure = IllegalStateException("The database is unreachable")
        val answer = lease.held {
            delay(AccountSlotRepository.SLOT_RENEWAL + 1.seconds)
            "answered"
        }
        assertEquals("answered", answer, "A renewal that fails is left to the next one")
        lease.release()
        slots.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { pool.release(Sharing.orgA, AccountSlot(Uuid.random(), Uuid.random())) }
    }

    @Test
    fun pastItsBoundAMemberPlacesNewSessionsWithoutForgettingItsOthers() = runTest {
        account("a", priority = 1)
        account("b")
        val pool = pool()
        suspend fun lease(session: String, member: Uuid = Sharing.memberA.memberId) =
            assertIs<LeaseResult.Leased>(pool.lease(Sharing.orgA, member, AccountProvider.CLAUDE, session)).lease
        fun prioritize(label: String, priority: Int) {
            val index = repository.accounts.indexOfFirst { it.label == label }
            repository.accounts[index] = repository.accounts[index].copy(priority = priority)
        }
        lease("kept").release()
        repeat(AccountSlotRepository.MAX_MEMBER_AFFINITIES - 1) { lease("s$it").release() }
        prioritize("b", 5)
        assertEquals("a", lease("kept").account.label, "Remembered: never evicted by newer sessions")
        assertEquals("b", lease("extra").account.label)
        prioritize("b", 0)
        assertEquals("a", lease("extra").account.label, "Over the bound: placed, not remembered")
        prioritize("b", 5)
        val other = lease("kept", Sharing.otherA.memberId)
        assertEquals("b", other.account.label, "Another member's same id is another session")
        clock.advance(AccountSlotRepository.AFFINITY + 1.minutes)
        assertEquals("b", lease("kept").account.label, "An affinity ends an hour after its last lease")
    }

    @Test
    fun anAccountNoLongerServingWhenItsRowIsLockedIsNotPicked() = runTest {
        val account = account("a")
        val locked = object : AccountSlotRepository by InMemorySlots(clock) {
            override suspend fun take(
                orgId: Uuid,
                memberId: Uuid,
                session: String?,
                candidates: List<SharedAccount>,
                pick: (SlotView) -> SharedAccount?,
            ): AccountSlot? = pick(SlotView(emptyMap(), null, emptySet()))?.let { AccountSlot(Uuid.random(), it.id) }
        }
        val pool = pool(repository, tokens(repository, cipher, refresher, clock), spend, clock, locked)
        val refused = assertIs<LeaseResult.Refused>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        )
        assertTrue("busy" in refused.refusal.reason, "Disabled between the read and the lock: no slot on it")
        assertEquals(AccountStatus.READY, repository.get(Sharing.orgA, account.id)!!.status)
    }
}
