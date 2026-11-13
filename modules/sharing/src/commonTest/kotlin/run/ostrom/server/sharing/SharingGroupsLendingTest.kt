// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.sharing.domain.AccountGroups
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.CredentialSecrets
import run.ostrom.server.sharing.domain.EnrollAccountPayload
import run.ostrom.server.sharing.domain.EnrollSharedAccountUseCaseImpl
import run.ostrom.server.sharing.domain.InvalidAccountException
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.ListSharedAccountsUseCaseImpl
import run.ostrom.server.sharing.domain.MemberNames
import run.ostrom.server.sharing.domain.NoAccountGroups
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.SecretCredential
import run.ostrom.server.sharing.domain.ShareAccountPayload
import run.ostrom.server.sharing.domain.ShareAccountUseCaseImpl
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.domain.SharedWith
import run.ostrom.server.sharing.domain.UpdateAccountPayload
import run.ostrom.server.sharing.domain.UpdateSharedAccountUseCaseImpl
import run.ostrom.server.sharing.domain.ViewSharedAccountUseCaseImpl
import run.ostrom.server.sharing.infrastructure.ProviderQuotaHeaderParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Accounts lent to groups through `sharedWith` (`group:<id>`): the groups are checked against the organization's,
 * named back sorted with the logins, and the pool serves a group's members who are not paused, reading the groups only
 * when an account names others than the member.
 */
class SharingGroupsLendingTest {
    private val repository = InMemoryAccounts()
    private val clock = TestClock()
    private val cipher = AccountTokenCipher(ContextCipher())
    private val share = ShareAccountUseCaseImpl(repository, cipher, clock)
    private val backend = Uuid.random()

    /** Logins of org A: `ada` the admin, `mia` the member. */
    private val logins = object : MemberLogins {
        val byId = mapOf(Sharing.adminA.memberId to "ada", Sharing.memberA.memberId to "mia")

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            logins.mapNotNull { login -> byId.entries.firstOrNull { it.value == login }?.let { login to it.key } }
                .toMap()

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            byId.filterKeys { it in memberIds }
    }

    /** Org A's one group, [backend], whose member not paused is `otherA`; what each account is lent to. */
    private val groups = object : AccountGroups {
        val lent = mutableMapOf<Uuid, Set<Uuid>>()
        var reads = 0

        override suspend fun of(orgId: Uuid, accountIds: Collection<Uuid>): Map<Uuid, Set<Uuid>> =
            lent.filter { (account, groups) -> account in accountIds && groups.isNotEmpty() }

        override suspend fun lend(orgId: Uuid, accountId: Uuid, groups: Set<Uuid>) {
            lent[accountId] = groups
        }

        override suspend fun existing(orgId: Uuid, groups: Set<Uuid>): Set<Uuid> =
            groups.filter { it == backend && orgId == Sharing.orgA }.toSet()

        override suspend fun lentTo(orgId: Uuid, memberId: Uuid, now: Instant): Set<Uuid> {
            reads++
            return if (memberId == Sharing.otherA.memberId) lent.filterValues { backend in it }.keys else emptySet()
        }
    }
    private val names = MemberNames(logins, groups)
    private val update = UpdateSharedAccountUseCaseImpl(repository, names)
    private val pool = SharedAccountPool(
        repository,
        repository,
        tokens(repository, cipher, FakeRefresher(), clock),
        InMemorySlots(clock, repository),
        ProviderQuotaHeaderParser(),
        NoAccountSpendReader(),
        clock,
        groups,
    )

    private suspend fun account(label: String, limits: AccountLimits) = share(
        Sharing.adminA,
        ShareAccountPayload(
            AccountProvider.CLAUDE, label, null, "acct-$label", "access-$label", "refresh-$label",
            clock.now() + 1.days, limits,
        ),
    )

    private suspend fun lease(member: Uuid) = pool.lease(Sharing.orgA, member, AccountProvider.CLAUDE, null)

    @Test
    fun sharedWithNamesMembersByLoginAndGroupsOfTheOrganizationOnly() = runTest {
        val group = "group:$backend"
        assertEquals(SharedWith(null, emptySet()), names.sharedWith(Sharing.orgA, emptyList()), "The whole org")
        val mia = setOf(Sharing.memberA.memberId)
        assertEquals(SharedWith(mia, emptySet()), names.sharedWith(Sharing.orgA, listOf("mia")))
        val only = names.sharedWith(Sharing.orgA, listOf(group))
        assertEquals(SharedWith(emptySet(), setOf(backend)), only, "No one else")
        assertEquals(SharedWith(mia, setOf(backend)), names.sharedWith(Sharing.orgA, listOf(group, "mia", group)))
        assertFailsWith<InvalidAccountException> { names.sharedWith(Sharing.orgA, listOf("group:backend")) }
        assertFailsWith<InvalidAccountException> { names.sharedWith(Sharing.orgA, listOf("group:${Uuid.random()}")) }
        assertFailsWith<InvalidAccountException> { names.sharedWith(Sharing.orgB, listOf(group)) }
        assertFailsWith<InvalidAccountException> { MemberNames(logins).sharedWith(Sharing.orgA, listOf(group)) }
    }

    @Test
    fun anAccountChangedOrEnrolledForAGroupIsLentToItAndNamesItBack() = runTest {
        val max = account("max", AccountLimits())
        val lent = update(Sharing.adminA, max.id, UpdateAccountPayload(sharedWith = listOf("mia", "group:$backend")))
        assertEquals(setOf(Sharing.memberA.memberId), lent.limits.allowedMembers)
        assertEquals(mapOf(max.id to setOf(backend)), groups.lent)
        val view = ViewSharedAccountUseCaseImpl(names)(Sharing.adminA, lent)
        assertEquals(listOf("group:$backend", "mia"), view.sharedWith)
        val list = ListSharedAccountsUseCaseImpl(repository, repository, pool, NoAccountSpendReader(), clock, names)
        assertEquals(listOf("group:$backend", "mia"), list(Sharing.adminA).single().sharedWith)
        assertEquals(emptyList(), list(Sharing.memberA).single().sharedWith, "Lent to whom is the admins'")

        val renamed = update(Sharing.adminA, max.id, UpdateAccountPayload(label = "Max"))
        assertEquals(setOf(backend), groups.lent.getValue(renamed.id), "No sharedWith: the groups stay")
        update(Sharing.adminA, max.id, UpdateAccountPayload(sharedWith = emptyList()))
        assertEquals(emptySet(), groups.lent.getValue(max.id), "The whole org: no group left")

        val secrets = CredentialSecrets { _, _ -> SecretCredential(Uuid.random(), "sk-ant-oat01-team") }
        val enroll = EnrollSharedAccountUseCaseImpl(secrets, names, share, update)
        val team = enroll(
            Sharing.adminA,
            EnrollAccountPayload(AccountProvider.CLAUDE, "Team", "TEAM", listOf("group:$backend")),
        )
        assertEquals(emptySet(), team.limits.allowedMembers, "Only a group: no one else")
        assertEquals(setOf(backend), groups.lent.getValue(team.id))
    }

    @Test
    fun thePoolServesAGroupsMembersAndReadsGroupsOnlyForAnAccountClosedToTheMember() = runTest {
        val open = account("open", AccountLimits())
        assertEquals(open.id, assertIs<LeaseResult.Leased>(lease(Sharing.memberA.memberId)).lease.account.id)
        assertEquals(0, groups.reads, "Every account open to the member: no group read")
        repository.accounts.clear()

        val team = account("team", AccountLimits(allowedMembers = emptySet()))
        groups.lent[team.id] = setOf(backend)
        val leased = assertIs<LeaseResult.Leased>(lease(Sharing.otherA.memberId)).lease
        assertEquals(team.id, leased.account.id, "A member of the group")
        leased.release()
        assertIs<LeaseResult.Refused>(lease(Sharing.memberA.memberId), "Not in the group")
        assertEquals(2, groups.reads)
    }

    @Test
    fun noGroupsLendNothing() = runTest {
        val id = Uuid.random()
        NoAccountGroups.lend(Sharing.orgA, id, setOf(backend))
        assertEquals(emptyMap(), NoAccountGroups.of(Sharing.orgA, listOf(id)))
        assertEquals(emptySet(), NoAccountGroups.existing(Sharing.orgA, setOf(backend)))
        assertEquals(emptySet(), NoAccountGroups.lentTo(Sharing.orgA, Sharing.memberA.memberId, clock.now()))
    }
}
