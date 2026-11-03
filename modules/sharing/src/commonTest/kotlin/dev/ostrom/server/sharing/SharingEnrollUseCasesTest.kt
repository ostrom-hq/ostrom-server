// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing

import dev.ostrom.server.kernel.auth.MemberLogins
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.FindSecretUseCase
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.Secret
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import dev.ostrom.server.secrets.domain.SecretValue
import dev.ostrom.server.sharing.domain.AccountLimits
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountSpendReader
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.AccountTokenCipher
import dev.ostrom.server.sharing.domain.EnrollAccountPayload
import dev.ostrom.server.sharing.domain.EnrollSharedAccountUseCaseImpl
import dev.ostrom.server.sharing.domain.InvalidAccountException
import dev.ostrom.server.sharing.domain.LeaseResult
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCaseImpl
import dev.ostrom.server.sharing.domain.MemberNames
import dev.ostrom.server.sharing.domain.NoAccountSpendReader
import dev.ostrom.server.sharing.domain.NoMemberLogins
import dev.ostrom.server.sharing.domain.RefreshResult
import dev.ostrom.server.sharing.domain.SecretCredential
import dev.ostrom.server.sharing.domain.ShareAccountPayload
import dev.ostrom.server.sharing.domain.ShareAccountUseCaseImpl
import dev.ostrom.server.sharing.domain.SharingRules
import dev.ostrom.server.sharing.domain.UpdateAccountPayload
import dev.ostrom.server.sharing.domain.UpdateSharedAccountUseCaseImpl
import dev.ostrom.server.sharing.domain.ViewSharedAccountUseCaseImpl
import dev.ostrom.server.sharing.infrastructure.SecretsCredentialSecrets
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The contract's enrollment: a subscription's token read from a secret, the people named by login both ways, a
 * long-lived token never renewed, and the daily allocation.
 */
class SharingEnrollUseCasesTest {
    private val repository = InMemoryAccounts()
    private val clock = TestClock()
    private val cipher = AccountTokenCipher(ContextCipher())
    private val share = ShareAccountUseCaseImpl(repository, cipher, clock)

    /** Logins of org A: the admin is `ada`, the member `mia`; `otherA` has none (it left). */
    private val logins = object : MemberLogins {
        val byId = mapOf(Sharing.adminA.memberId to "ada", Sharing.memberA.memberId to "mia")

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            logins.mapNotNull { login ->
                byId.entries.firstOrNull { it.value == login.lowercase() }?.let { login to it.key }
            }.toMap().takeIf { orgId == Sharing.orgA }.orEmpty()

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            byId.filterKeys { it in memberIds }.takeIf { orgId == Sharing.orgA }.orEmpty()
    }
    private val names = MemberNames(logins)

    /** Secrets of org A: `ADA_MAX` is the admin's own, `ORG_MAX` the org's, `GONE` deleted between two reads. */
    private val secretIds = mapOf("ADA_MAX" to Uuid.random(), "ORG_MAX" to Uuid.random(), "GONE" to Uuid.random())
    private val find = object : FindSecretUseCase {
        override suspend fun invoke(caller: Caller, name: String, personal: Boolean): Secret {
            val owner = Sharing.adminA.memberId.takeIf { name == "ADA_MAX" }
            val id = secretIds[name]
            val mine = if (owner == null) !personal else personal && owner == caller.memberId
            if (id == null || !mine) {
                throw SecretNotFoundException()
            }
            return Secret(id, caller.orgId, name, owner, 1, clock.now(), clock.now(), caller.memberId)
        }
    }
    private var revealed = 0
    private val reveal = object : RevealSecretsUseCase {
        override suspend fun invoke(orgId: Uuid, ids: Collection<Uuid>): Map<Uuid, SecretValue> {
            revealed++
            return ids.filter { it != secretIds["GONE"] }.associateWith { SecretValue(" sk-ant-oat01-$it\n") }
        }

        override suspend fun byName(orgId: Uuid, names: Collection<String>): Map<String, SecretValue> = emptyMap()
    }
    private val secrets = SecretsCredentialSecrets(find, reveal)
    private val enroll = EnrollSharedAccountUseCaseImpl(secrets, names, share)

    @Test
    fun anAdminLendsTheOrganizationsSecretAndAMemberOnlyTheirOwn() = runTest {
        val own = assertIs<SecretCredential>(secrets.read(Sharing.adminA, "ADA_MAX"))
        assertEquals(secretIds.getValue("ADA_MAX"), own.id)
        assertFalse("sk-ant" in own.toString(), "Never in logs")
        assertEquals(secretIds.getValue("ORG_MAX"), secrets.read(Sharing.adminA, "ORG_MAX")?.id)
        revealed = 0
        assertNull(secrets.read(Sharing.memberA, "ORG_MAX"), "The organization's secret is its admins'")
        assertNull(secrets.read(Sharing.memberA, "ADA_MAX"), "Another member's secret")
        assertEquals(0, revealed, "Nothing decrypted for a refusal")
        assertNull(secrets.read(Sharing.adminA, "GONE"), "Deleted between the two reads")
        assertNull(secrets.read(Sharing.adminA, "NONE"))
    }

    @Test
    fun anEnrolledAccountHoldsTheSecretsTokenAsALongLivedOneLentByLogin() = runTest {
        val payload = EnrollAccountPayload(AccountProvider.CLAUDE, "Ada Max", "ORG_MAX", listOf("MIA"), 25_000_000)
        val account = enroll(Sharing.adminA, payload)
        assertEquals("secret:${secretIds.getValue("ORG_MAX")}", account.providerAccountId)
        assertEquals(setOf(Sharing.memberA.memberId), account.limits.allowedMembers)
        assertEquals(25_000_000, account.limits.dailyBudgetMicros)
        val stored = cipher.open(repository.envelopes.getValue(account.id), Sharing.orgA, account.id)
        assertEquals("sk-ant-oat01-${secretIds.getValue("ORG_MAX")}", stored?.accessToken, "Trimmed")
        assertEquals("", stored?.refreshToken)
        assertEquals(Instant.DISTANT_FUTURE, stored?.expiresAt)

        // Enrolled again: the same account, its limits kept.
        val again = enroll(Sharing.adminA, payload.copy(sharedWith = emptyList(), dailyBudgetMicros = null))
        assertEquals(account.id, again.id)
        assertEquals(setOf(Sharing.memberA.memberId), again.limits.allowedMembers)

        assertFailsWith<SecretNotFoundException> { enroll(Sharing.memberA, payload.copy(sharedWith = emptyList())) }
        assertFailsWith<InvalidAccountException> { enroll(Sharing.adminA, payload.copy(sharedWith = listOf("zed"))) }
    }

    @Test
    fun peopleAreNamedByLoginAndByIdWhenTheyHaveNone() = runTest {
        assertNull(names.ids(Sharing.orgA, emptyList()), "Empty: the whole organization")
        val legacy = Uuid.random()
        assertEquals(
            setOf(Sharing.memberA.memberId, legacy),
            names.ids(Sharing.orgA, listOf("mia", legacy.toString(), "Mia")),
        )
        assertFailsWith<InvalidAccountException> { names.ids(Sharing.orgB, listOf("mia")) }
        assertFailsWith<InvalidAccountException> {
            names.ids(Sharing.orgA, List(SharingRules.MAX_ALLOWED_MEMBERS + 1) { "mia" })
        }
        assertEquals(emptyMap(), NoMemberLogins.idsOf(Sharing.orgA, listOf("mia")))
        assertEquals(emptyMap(), NoMemberLogins.loginsOf(Sharing.orgA, listOf(Sharing.memberA.memberId)))

        val lent = share(
            Sharing.memberA,
            tokens("acct-1", AccountLimits(allowedMembers = setOf(Sharing.otherA.memberId, Sharing.adminA.memberId))),
        )
        val view = ViewSharedAccountUseCaseImpl(names)(Sharing.memberA, lent)
        assertEquals("mia", view.owner)
        assertEquals(listOf(Sharing.otherA.memberId.toString(), "ada").sorted(), view.sharedWith)
        val ownerless = ViewSharedAccountUseCaseImpl(MemberNames(NoMemberLogins))(
            Sharing.adminA,
            lent.copy(ownerMemberId = null, limits = AccountLimits()),
        )
        assertEquals("", ownerless.owner)
        assertEquals(emptyList(), ownerless.sharedWith)

        val pool = pool(repository, cipher, FakeRefresher(), NoAccountSpendReader(), clock)
        val list = ListSharedAccountsUseCaseImpl(repository, repository, pool, NoAccountSpendReader(), clock, names)
        assertEquals(listOf("mia"), list(Sharing.adminA).map { it.owner })
        val hidden = list(Sharing.otherA).single()
        assertEquals("", hidden.owner)
        assertEquals(emptyList(), hidden.sharedWith, "Lent to whom is the admins' and its owner's")

        val update = UpdateSharedAccountUseCaseImpl(repository, names)
        val moved = update(Sharing.memberA, lent.id, UpdateAccountPayload(sharedWith = listOf("ADA")))
        assertEquals(setOf(Sharing.adminA.memberId), moved.limits.allowedMembers)
        val open = update(Sharing.memberA, lent.id, UpdateAccountPayload(sharedWith = emptyList()))
        assertNull(open.limits.allowedMembers)
    }

    @Test
    fun aLongLivedTokenIsNeverRenewedAndItsAccountWaitsForItsOwnerOnceRefused() = runTest {
        val refresher = FakeRefresher(RefreshResult.Revoked)
        val tokens = tokens(repository, cipher, refresher, clock)
        val account = enroll(Sharing.adminA, EnrollAccountPayload(AccountProvider.CODEX, "Codex", "ADA_MAX"))
        clock.advance(3650.days)
        assertEquals("sk-ant-oat01-${secretIds.getValue("ADA_MAX")}", tokens.fresh(account)?.accessToken)
        tokens.expire(account)
        assertNull(tokens.fresh(account))
        assertEquals(0, refresher.calls, "Nothing to renew it with")
        assertEquals(AccountStatus.RECONNECT_REQUIRED, repository.get(Sharing.orgA, account.id)?.status)
        assertFailsWith<InvalidAccountException> {
            SharingRules.requireLimits(AccountLimits(dailyBudgetMicros = -1))
        }
    }

    @Test
    fun aSpentDailyAllocationClosesTheAccountUntilTheNextUtcDay() = runTest {
        var spent = 0L
        val spend = AccountSpendReader { _, since -> if (since == today()) spent else 0 }
        val pool = pool(repository, cipher, FakeRefresher(), spend, clock)
        share(Sharing.memberA, tokens("acct-1", AccountLimits(dailyBudgetMicros = 1_000)))
        val lease = assertIs<LeaseResult.Leased>(pool.lease(Sharing.orgA, Sharing.otherA.memberId, CLAUDE, null))
        lease.lease.release()
        spent = 1_000
        val refused = assertIs<LeaseResult.Refused>(pool.lease(Sharing.orgA, Sharing.otherA.memberId, CLAUDE, null))
        assertEquals(today() + 1.days, refused.refusal.retryAt)
    }

    private fun today() = Instant.fromEpochSeconds(clock.now().epochSeconds / DAY * DAY)

    private fun tokens(id: String, limits: AccountLimits) = ShareAccountPayload(
        AccountProvider.CLAUDE,
        "Mia Max",
        null,
        id,
        "sk-ant-oat01-access",
        "sk-ant-ort01-refresh",
        clock.now() + 8.hours,
        limits,
    )

    private companion object {
        const val DAY = 86_400L
        val CLAUDE = AccountProvider.CLAUDE
    }
}
