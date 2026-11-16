// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.InvalidPolicyException
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.MemberPoliciesUseCaseImpl
import run.ostrom.server.sharing.domain.MemberPolicy
import run.ostrom.server.sharing.domain.MemberPoolRepository
import run.ostrom.server.sharing.domain.NoAccountGroups
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.NoMemberPools
import run.ostrom.server.sharing.domain.NoQuotaHistory
import run.ostrom.server.sharing.domain.OrgMemberNotFoundException
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.infrastructure.ProviderQuotaHeaderParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** The members' home pools in memory, counting the reads of one member's pool. */
class InMemoryMemberPools : MemberPoolRepository {
    val pools = mutableMapOf<Pair<Uuid, Uuid>, String>()
    var reads = 0

    override suspend fun poolOf(orgId: Uuid, memberId: Uuid): String? {
        reads++
        return pools[orgId to memberId]
    }

    override suspend fun poolsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
        memberIds.mapNotNull { id -> pools[orgId to id]?.let { id to it } }.toMap()

    override suspend fun setPool(orgId: Uuid, memberId: Uuid, poolId: String?) {
        if (poolId == null) pools -= orgId to memberId else pools[orgId to memberId] = poolId
    }
}

/** TeamCode's member pool (`MemberPolicy.poolId`): who sets it, its form, and the accounts it lets serve. */
class MemberPoolsTest {
    private val repository = InMemoryAccounts()
    private val clock = TestClock()
    private val cipher = AccountTokenCipher(ContextCipher())
    private val refresher = FakeRefresher(RefreshResult.Revoked)
    private val pools = InMemoryMemberPools()
    private val logins = object : MemberLogins {
        val byId = mapOf(Sharing.adminA.memberId to "ada", Sharing.memberA.memberId to "mia")

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            byId.entries.filter { it.value in logins }.associate { it.value to it.key }

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            byId.filterKeys { it in memberIds }
    }
    private val policies = MemberPoliciesUseCaseImpl(pools, logins)
    private val pool = SharedAccountPool(
        repository,
        repository,
        tokens(repository, cipher, refresher, clock),
        InMemorySlots(clock, repository),
        ProviderQuotaHeaderParser(),
        NoAccountSpendReader(),
        clock,
        NoAccountGroups,
        JsonLogger(LogLevel.ERROR, sink = {}),
        NoQuotaHistory,
        pools,
    )

    /** An account of [poolId]; one of a higher [priority] is tried first, and its tokens cannot be renewed. */
    private suspend fun account(label: String, poolId: String?, priority: Int = 0): SharedAccount {
        val account = SharedAccount(
            Uuid.random(), Sharing.orgA, AccountProvider.CLAUDE, label, null, "acct-$label", null, priority,
            AccountStatus.READY, AccountLimits(), null, clock.now(), poolId = poolId,
        )
        repository.accounts += account
        val expiresAt = if (priority > 0) clock.now() else clock.now() + 1.days
        repository.envelopes[account.id] =
            cipher.seal(AccountCredentials("access-$label", "refresh-$label", expiresAt), Sharing.orgA, account.id)
        return account
    }

    private suspend fun leased(member: Uuid = Sharing.memberA.memberId): String? =
        when (val result = pool.lease(Sharing.orgA, member, AccountProvider.CLAUDE, null)) {
            is LeaseResult.Leased -> result.lease.account.label.also { result.lease.release() }
            is LeaseResult.Refused -> null
        }

    @Test
    fun anAdminSetsAMembersPoolAndAnEmptyStringClearsIt() = runTest {
        val set = policies.setPool(Sharing.adminA, "mia", "eu-1_a")
        assertEquals(MemberPolicy("mia", Sharing.memberA.memberId, "eu-1_a"), set)
        assertEquals("eu-1_a", pools.pools[Sharing.orgA to Sharing.memberA.memberId])
        assertEquals(set, policies.get(Sharing.adminA, "mia"), "An admin reads any member's")
        assertEquals(set, policies.get(Sharing.memberA, "mia"), "A member reads their own")
        assertNull(policies.setPool(Sharing.adminA, "mia", "").poolId)
        assertEquals(emptyMap(), pools.pools)
        assertNull(policies.get(Sharing.memberA, "mia").poolId)
    }

    @Test
    fun aMemberChangesNoPoolAndReadsNoOtherMembers() = runTest {
        assertFailsWith<AdminRequiredException> { policies.setPool(Sharing.memberA, "mia", "eu") }
        assertFailsWith<AdminRequiredException> { policies.get(Sharing.memberA, "ada") }
        assertFailsWith<AdminRequiredException>("No login enumeration") { policies.get(Sharing.memberA, "ghost") }
        assertEquals(emptyMap(), pools.pools)
    }

    @Test
    fun aMalformedPoolOrAnUnknownLoginIsRefused() = runTest {
        for (value in listOf("eu west", "a".repeat(37), "eu/1", "é")) {
            assertFailsWith<InvalidPolicyException>(value) { policies.setPool(Sharing.adminA, "mia", value) }
        }
        assertEquals("invalid_policy", InvalidPolicyException().type)
        assertEquals("a".repeat(36), policies.setPool(Sharing.adminA, "mia", "a".repeat(36)).poolId)
        assertFailsWith<OrgMemberNotFoundException> { policies.setPool(Sharing.adminA, "ghost", "eu") }
        assertFailsWith<OrgMemberNotFoundException> { policies.get(Sharing.adminA, "ghost") }
    }

    @Test
    fun aMemberIsServedOnlyByTheAccountsOfTheirHomePool() = runTest {
        account("none", null)
        account("eu", "eu")
        account("us", "us")
        pools.setPool(Sharing.orgA, Sharing.memberA.memberId, "eu")
        repeat(3) { assertEquals("eu", leased(), "A member of eu leases only eu") }
        assertEquals("none", leased(Sharing.adminA.memberId), "A member in no pool leases only the accounts in none")
        pools.setPool(Sharing.orgA, Sharing.memberA.memberId, "asia")
        val refused = assertIs<LeaseResult.Refused>(
            pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
        )
        assertEquals("No shared claude account is available to you.", refused.refusal.reason)
        assertNull(refused.refusal.retryAt)
    }

    @Test
    fun theHomePoolIsReadOncePerLeaseWhateverTheAccountsTried() = runTest {
        // Two accounts of the pool whose tokens cannot be renewed: the lease tries both, then a third that serves.
        account("a", "eu", priority = 10)
        account("b", "eu", priority = 5)
        account("c", "eu")
        pools.setPool(Sharing.orgA, Sharing.memberA.memberId, "eu")
        pools.reads = 0
        assertEquals("c", leased())
        assertEquals(1, pools.reads)
    }

    @Test
    fun noMemberPoolsPutsEveryoneInNone() = runTest {
        NoMemberPools.setPool(Sharing.orgA, Sharing.memberA.memberId, "eu")
        assertNull(NoMemberPools.poolOf(Sharing.orgA, Sharing.memberA.memberId))
        assertEquals(emptyMap(), NoMemberPools.poolsOf(Sharing.orgA, listOf(Sharing.memberA.memberId)))
    }
}
