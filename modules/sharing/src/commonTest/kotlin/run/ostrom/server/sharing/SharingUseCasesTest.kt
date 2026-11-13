// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.sharing.domain.AccountChangedException
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountNotFoundException
import run.ostrom.server.sharing.domain.AccountOwnedByAnotherMemberException
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.InvalidAccountException
import run.ostrom.server.sharing.domain.ListSharedAccountsUseCaseImpl
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.RemoveSharedAccountUseCaseImpl
import run.ostrom.server.sharing.domain.ShareAccountPayload
import run.ostrom.server.sharing.domain.ShareAccountUseCaseImpl
import run.ostrom.server.sharing.domain.SharingRules
import run.ostrom.server.sharing.domain.TooManyAccountsException
import run.ostrom.server.sharing.domain.UpdateAccountPayload
import run.ostrom.server.sharing.domain.UpdateSharedAccountUseCaseImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

class SharingUseCasesTest {
    private val repository = InMemoryAccounts()
    private val clock = TestClock()
    private val cipher = AccountTokenCipher(ContextCipher())
    private val share = ShareAccountUseCaseImpl(repository, cipher, clock)
    private val pool = pool(repository, cipher, FakeRefresher(), NoAccountSpendReader(), clock)
    private val list = ListSharedAccountsUseCaseImpl(repository, repository, pool, { _, _ -> 42 }, clock)
    private val update = UpdateSharedAccountUseCaseImpl(repository)
    private val remove = RemoveSharedAccountUseCaseImpl(repository)

    private fun payload(id: String = "acct-1", label: String = "Lyes Max", limits: AccountLimits = AccountLimits()) =
        ShareAccountPayload(
            AccountProvider.CLAUDE,
            label,
            "lyes@example.com",
            id,
            "sk-ant-oat01-access",
            "sk-ant-ort01-refresh",
            clock.now() + 8.hours,
            limits,
        )

    @Test
    fun aMemberSharesItsAccountSealedAndOwned() = runTest {
        val account = share(Sharing.memberA, payload())
        assertEquals(Sharing.memberA.memberId, account.ownerMemberId)
        assertEquals(AccountStatus.READY, account.status)
        val envelope = repository.envelopes.getValue(account.id)
        assertEquals("sk-ant-oat01-access", cipher.open(envelope, Sharing.orgA, account.id)!!.accessToken)
        assertFalse(payload().toString().contains("sk-ant"), "Tokens never in logs")
    }

    @Test
    fun sharingAgainReplacesTokensOnlyForItsOwnerOrAnAdmin() = runTest {
        val first = share(Sharing.memberA, payload())
        update(Sharing.memberA, first.id, UpdateAccountPayload(disabled = true, priority = 3))
        val again = share(Sharing.memberA, payload(label = "Renamed"))
        assertEquals(first.id, again.id)
        assertEquals(AccountStatus.DISABLED, again.status, "Re-sharing keeps it off")
        assertEquals(3, again.priority)
        assertFailsWith<AccountOwnedByAnotherMemberException> { share(Sharing.otherA, payload()) }
        assertEquals(Sharing.memberA.memberId, share(Sharing.adminA, payload()).ownerMemberId)
    }

    @Test
    fun onlyANewOrDisconnectedAccountIsStoredOffAndSharingAgainNeverTurnsOneOn() = runTest {
        val off = share(Sharing.adminA, payload(id = "acct-off").copy(disabled = true))
        assertEquals(AccountStatus.DISABLED, off.status, "Stored off in the same write")
        assertEquals(AccountStatus.DISABLED, share(Sharing.adminA, payload(id = "acct-off")).status, "Never on again")
        val on = share(Sharing.adminA, payload())
        val serving = share(Sharing.adminA, payload().copy(disabled = true))
        assertEquals(AccountStatus.READY, serving.status, "Re-sharing a serving account never turns it off")
        repository.setStatus(Sharing.orgA, on.id, AccountStatus.RECONNECT_REQUIRED)
        assertEquals(AccountStatus.DISABLED, share(Sharing.adminA, payload().copy(disabled = true)).status)
    }

    @Test
    fun aConditionalWriteNeverOverwritesAChangeMadeSinceItsRead() = runTest {
        assertFailsWith<AccountChangedException> { share(Sharing.adminA, payload().copy(ifVersion = 3)) }
        assertEquals(emptyList(), repository.list(Sharing.orgA), "Nothing written")
        val first = share(Sharing.adminA, payload().copy(ifVersion = 0))
        assertEquals(1L, first.version)
        assertFailsWith<AccountChangedException>("The org has it now") {
            share.check(Sharing.adminA, payload().copy(ifVersion = 0))
        }
        assertEquals(2L, share(Sharing.adminA, payload(label = "Again").copy(ifVersion = 1)).version)
        assertFailsWith<AccountChangedException> {
            update(Sharing.adminA, first.id, UpdateAccountPayload(disabled = true, ifVersion = 1))
        }
        assertEquals(AccountStatus.READY, repository.get(Sharing.orgA, first.id)!!.status, "Nothing overwritten")
        val off = update(Sharing.adminA, first.id, UpdateAccountPayload(disabled = true, ifVersion = 2))
        assertEquals(AccountStatus.DISABLED, off.status)
    }

    @Test
    fun aCheckRefusesWhatAShareWouldAndWritesNothing() = runTest {
        share.check(Sharing.memberA, payload())
        assertEquals(emptyList(), repository.list(Sharing.orgA), "A check writes nothing")
        assertFailsWith<InvalidAccountException> { share.check(Sharing.memberA, payload(label = "")) }
        share(Sharing.memberA, payload())
        assertFailsWith<AccountOwnedByAnotherMemberException> { share.check(Sharing.otherA, payload()) }
    }

    @Test
    fun invalidInputIsRefused() = runTest {
        assertFailsWith<InvalidAccountException> { share(Sharing.memberA, payload(label = "")) }
        assertFailsWith<InvalidAccountException> { share(Sharing.memberA, payload(id = "bad id")) }
        assertFailsWith<InvalidAccountException> { share(Sharing.memberA, payload().copy(accessToken = "short")) }
        assertFailsWith<InvalidAccountException> { share(Sharing.memberA, payload().copy(email = "a\nb")) }
        assertFailsWith<InvalidAccountException> { share(Sharing.memberA, payload().copy(email = "a".repeat(321))) }
        assertFailsWith<InvalidAccountException> { share(Sharing.memberA, payload().copy(refreshToken = "short")) }
        for (limits in listOf(
            AccountLimits(maxConcurrent = 0),
            AccountLimits(maxConcurrent = 65),
            AccountLimits(utilizationCeiling = 0.01),
            AccountLimits(utilizationCeiling = 2.0),
        )) {
            assertFailsWith<InvalidAccountException>(limits.toString()) {
                share(Sharing.memberA, payload(limits = limits))
            }
        }
        assertFailsWith<InvalidAccountException> {
            share(Sharing.memberA, payload(limits = AccountLimits(utilizationCeiling = Double.NaN)))
        }
        assertFailsWith<InvalidAccountException> {
            share(Sharing.memberA, payload(limits = AccountLimits(monthlyBudgetMicros = -1)))
        }
        val many = (0..1000).map { Uuid.random() }.toSet()
        assertFailsWith<InvalidAccountException> {
            share(Sharing.memberA, payload(limits = AccountLimits(allowedMembers = many)))
        }
        val account = share(Sharing.memberA, payload())
        for (priority in listOf(-101, 101)) {
            assertFailsWith<InvalidAccountException> {
                update(Sharing.memberA, account.id, UpdateAccountPayload(priority = priority))
            }
        }
        assertFailsWith<InvalidAccountException> {
            update(Sharing.memberA, account.id, UpdateAccountPayload(limits = { AccountLimits(maxConcurrent = 0) }))
        }
        val accepted = share(
            Sharing.memberA,
            payload(
                id = "with-limits",
                limits = AccountLimits(monthlyBudgetMicros = 5, allowedMembers = many.take(3).toSet()),
            )
                .copy(email = null),
        )
        assertEquals(5, accepted.limits.monthlyBudgetMicros)
        assertFailsWith<InvalidAccountException> {
            update(Sharing.memberA, account.id, UpdateAccountPayload(label = ""))
        }
    }

    @Test
    fun anOrgSharesAtMostAHundredAccounts() = runTest {
        repeat(SharingRules.MAX_ACCOUNTS) { share(Sharing.adminA, payload(id = "acct-$it")) }
        assertFailsWith<TooManyAccountsException> { share(Sharing.adminA, payload(id = "one-more")) }
        share(Sharing.adminA, payload(id = "acct-1", label = "Same account, new tokens"))
    }

    @Test
    fun membersSeeNeutralLabelsForAccountsOfOthers() = runTest {
        val mine = share(Sharing.memberA, payload(id = "mine", label = "Mine"))
        share(Sharing.otherA, payload(id = "theirs", label = "Theirs", limits = AccountLimits(monthlyBudgetMicros = 9)))
        repository.windows[mine.id] = mutableListOf(
            QuotaWindow("seven_day", 0.2, clock.now() + 1.hours, clock.now()),
            QuotaWindow("five_hour", 0.4, clock.now() - 1.hours, clock.now()),
        )
        val seen = list(Sharing.memberA)
        val own = seen.single { it.identityVisible }
        assertEquals("Mine", own.displayLabel)
        assertEquals(listOf("seven_day"), own.windows.map { it.window })
        assertEquals(42, own.spentMicros)
        val other = seen.single { !it.identityVisible }
        assertTrue(other.displayLabel.startsWith("Account "))
        assertEquals("", other.account.providerAccountId)
        assertEquals(null, other.account.email)
        assertTrue(list(Sharing.adminA).all { it.identityVisible })
        assertEquals(emptyList(), list(Sharing.adminB))
    }

    @Test
    fun onlyAdminsAndOwnersChangeOrRemove() = runTest {
        val account = share(Sharing.memberA, payload())
        assertFailsWith<AccountNotFoundException> {
            update(Sharing.otherA, account.id, UpdateAccountPayload(label = "x"))
        }
        assertFailsWith<AccountNotFoundException> { remove(Sharing.otherA, account.id) }
        assertFailsWith<AccountNotFoundException> { remove(Sharing.adminB, account.id) }
        assertFailsWith<AccountNotFoundException> { update(Sharing.adminA, Uuid.random(), UpdateAccountPayload()) }
        val changed =
            update(
                Sharing.adminA,
                account.id,
                UpdateAccountPayload(label = "Team", limits = { it.copy(maxConcurrent = 2) }),
            )
        assertEquals("Team", changed.label)
        assertEquals(2, changed.limits.maxConcurrent)
        val off = update(Sharing.memberA, account.id, UpdateAccountPayload(disabled = true))
        assertEquals(AccountStatus.DISABLED, off.status)
        assertEquals(
            AccountStatus.READY,
            update(Sharing.memberA, account.id, UpdateAccountPayload(disabled = false)).status,
        )
        repository.setStatus(Sharing.orgA, account.id, AccountStatus.RECONNECT_REQUIRED)
        assertEquals(
            AccountStatus.RECONNECT_REQUIRED,
            update(Sharing.memberA, account.id, UpdateAccountPayload(disabled = false)).status,
        )
        repository.setStatus(Sharing.orgA, account.id, AccountStatus.WRONG_ACCOUNT)
        assertEquals(
            AccountStatus.WRONG_ACCOUNT,
            update(Sharing.memberA, account.id, UpdateAccountPayload(disabled = false)).status,
            "Resuming never serves another account's tokens",
        )
        assertEquals(AccountStatus.READY, share(Sharing.memberA, payload()).status, "Sharing it again does")
        remove(Sharing.memberA, account.id)
        assertEquals(emptyList(), repository.accounts)
    }

    @Test
    fun anAccountRemovedMeanwhileIsNotFound() = runTest {
        val account = share(Sharing.memberA, payload())
        repository.vanishing = true
        assertFailsWith<AccountNotFoundException> { update(Sharing.memberA, account.id, UpdateAccountPayload()) }
        assertFailsWith<AccountNotFoundException> { remove(Sharing.memberA, account.id) }
    }

    @Test
    fun anOwnerlessAccountGoesToWhoSharesItAgainAndProvidersStayApart() = runTest {
        val first = share(Sharing.memberA, payload())
        repository.accounts[0] = first.copy(ownerMemberId = null, status = AccountStatus.RECONNECT_REQUIRED)
        clock.advance(1.hours)
        val again = share(Sharing.adminA, payload(label = "Team Max"))
        assertEquals(first.id, again.id)
        assertEquals(Sharing.adminA.memberId, again.ownerMemberId)
        assertEquals(AccountStatus.READY, again.status, "New tokens reconnect it")
        assertEquals(first.createdAt, again.createdAt)
        val codex = share(Sharing.memberA, payload().copy(provider = AccountProvider.CODEX))
        assertNotEquals(first.id, codex.id, "Same account id, another provider: another account")
        assertEquals(2, repository.accounts.size)
    }
}
