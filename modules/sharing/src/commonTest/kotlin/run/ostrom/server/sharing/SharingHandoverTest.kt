// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing

import run.ostrom.server.sharing.domain.AccountHandoverForbiddenException
import run.ostrom.server.sharing.domain.AccountInUseException
import run.ostrom.server.sharing.domain.AccountNotFoundException
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountRefreshUncertainException
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.HandOverSharedAccountUseCaseImpl
import run.ostrom.server.sharing.domain.InvalidAccountException
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.sharing.domain.ShareAccountPayload
import run.ostrom.server.sharing.domain.ShareAccountUseCaseImpl
import run.ostrom.server.sharing.domain.UpdateAccountPayload
import run.ostrom.server.sharing.domain.UpdateSharedAccountUseCaseImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * TeamCode's refresh ownership (docs/capacity.md « Refresh ownership »): an imported account waits for its handover and
 * serves nothing, an access-only one serves its access token and is never renewed, a handover gives the refresh to
 * Ostrom.
 */
class SharingHandoverTest {
    private val repository = InMemoryAccounts()
    private val clock = TestClock()
    private val cipher = AccountTokenCipher(ContextCipher())
    private val refresher = FakeRefresher(RefreshResult.Revoked)
    private val share = ShareAccountUseCaseImpl(repository, cipher, clock)
    private val update = UpdateSharedAccountUseCaseImpl(repository)
    private val handOver = HandOverSharedAccountUseCaseImpl(repository)

    private fun imported(ownership: RefreshOwnership?, expiresAt: Instant = clock.now() + 8.hours) =
        ShareAccountPayload(
            AccountProvider.CLAUDE,
            "Imported Max",
            null,
            "acct-imported",
            "access-imported",
            "refresh-imported",
            expiresAt,
            ownership = ownership,
        )

    private suspend fun lease() = pool(repository, cipher, refresher, NoAccountSpendReader(), clock)
        .lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null)

    @Test
    fun anAccountWaitingForItsHandoverServesNothingUntilAnAdminHandsItOver() = runTest {
        val account = share(Sharing.adminA, imported(RefreshOwnership.PENDING_HANDOVER))
        assertEquals(RefreshOwnership.PENDING_HANDOVER, account.ownership)
        assertFalse(account.serves)
        assertIs<LeaseResult.Refused>(lease(), "Its source may still renew the tokens")
        // Paused and resumed, it keeps waiting.
        update(Sharing.adminA, account.id, UpdateAccountPayload(disabled = true))
        val resumed = update(Sharing.adminA, account.id, UpdateAccountPayload(disabled = false))
        assertEquals(RefreshOwnership.PENDING_HANDOVER, resumed.ownership)

        val handed = handOver(Sharing.adminA, account.id, RefreshOwnership.OSTROM)
        assertEquals(RefreshOwnership.OSTROM, handed.ownership)
        assertTrue(handed.version > resumed.version, "A handover is an edit of the row")
        val leased = assertIs<LeaseResult.Leased>(lease()).lease
        assertEquals("access-imported", leased.credentials.accessToken)
        assertEquals(handed, handOver(Sharing.adminA, account.id, RefreshOwnership.OSTROM), "Already Ostrom's")
    }

    @Test
    fun anAccessOnlyAccountServesItsAccessTokenUntilDueThenNothingAndNeverRenews() = runTest {
        val account = share(Sharing.adminA, imported(RefreshOwnership.ACCESS_ONLY, clock.now() + 1.hours))
        assertTrue(account.serves)
        assertEquals("access-imported", assertIs<LeaseResult.Leased>(lease()).lease.credentials.accessToken)
        // Within the refresh margin: the refresh token is never sent, the account stays as it is.
        clock.advance(1.hours - AccountTokens.REFRESH_MARGIN + 1.minutes)
        assertIs<LeaseResult.Refused>(lease())
        assertEquals(0, refresher.calls, "Its source renews its own copy")
        val now = repository.get(Sharing.orgA, account.id)!!
        assertEquals(AccountStatus.READY to RefreshOwnership.ACCESS_ONLY, now.status to now.ownership)
        // Imported again with fresh tokens, it serves again.
        share(Sharing.adminA, imported(RefreshOwnership.ACCESS_ONLY, clock.now() + 1.hours))
        assertIs<LeaseResult.Leased>(lease())
    }

    @Test
    fun onlyAnAdminHandsOverAndNeverWhileARefreshIsInFlightOrUncertain() = runTest {
        val mine = share(Sharing.memberA, imported(null))
        handOver(Sharing.adminA, mine.id, RefreshOwnership.ACCESS_ONLY)
        assertFailsWith<AccountHandoverForbiddenException> {
            handOver(Sharing.memberA, mine.id, RefreshOwnership.OSTROM)
        }
        assertFailsWith<AccountNotFoundException>("Not theirs: as if absent") {
            handOver(Sharing.otherA, mine.id, RefreshOwnership.OSTROM)
        }
        assertFailsWith<AccountNotFoundException>("Another org's") {
            handOver(Sharing.adminB, mine.id, RefreshOwnership.OSTROM)
        }
        assertFailsWith<InvalidAccountException>("Nobody puts an account back to waiting") {
            handOver(Sharing.adminA, mine.id, RefreshOwnership.PENDING_HANDOVER)
        }
        val ostrom = share(Sharing.adminA, imported(null).copy(providerAccountId = "acct-ostrom"))
        repository.pending += ostrom.id
        assertFailsWith<AccountRefreshUncertainException> {
            handOver(Sharing.adminA, ostrom.id, RefreshOwnership.ACCESS_ONLY)
        }
        assertEquals(RefreshOwnership.OSTROM, repository.get(Sharing.orgA, ostrom.id)!!.ownership)
    }

    @Test
    fun aFreshShareTakesAnImportedAccountOverOnlyForAnAdmin() = runTest {
        // An import names the ownership it decided; a share from a sign-in names none, so Ostrom renews it.
        val waiting = share(Sharing.memberA, imported(RefreshOwnership.PENDING_HANDOVER))
        assertFailsWith<AccountInUseException>("The member would undo an admin's handover decision") {
            share(Sharing.memberA, imported(null))
        }
        assertFailsWith<AccountInUseException> { share.check(Sharing.memberA, imported(null)) }
        assertEquals(RefreshOwnership.PENDING_HANDOVER, repository.get(Sharing.orgA, waiting.id)!!.ownership)
        val reimported = share(Sharing.memberA, imported(RefreshOwnership.PENDING_HANDOVER))
        assertEquals(RefreshOwnership.PENDING_HANDOVER, reimported.ownership, "An import keeps its decision")
        assertEquals(RefreshOwnership.OSTROM, share(Sharing.adminA, imported(null)).ownership)
        assertEquals(RefreshOwnership.OSTROM, share(Sharing.memberA, imported(null)).ownership, "Ostrom's now")
        assertEquals(
            "access-imported",
            cipher.open(repository.envelopes.getValue(waiting.id), Sharing.orgA, waiting.id)?.accessToken,
        )
    }

    @Test
    fun aWaitingAccountIsNeverRenewedNorRetired() = runTest {
        val account = share(Sharing.adminA, imported(RefreshOwnership.PENDING_HANDOVER, clock.now() + 1.minutes))
        val tokens = tokens(repository, cipher, refresher, clock)
        assertEquals(null, tokens.fresh(account))
        assertEquals(0, refresher.calls)
        assertEquals(AccountStatus.READY, repository.get(Sharing.orgA, account.id)!!.status)
    }
}
