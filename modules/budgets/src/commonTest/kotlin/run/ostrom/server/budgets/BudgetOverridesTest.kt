// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.ActiveBudgetOverride
import run.ostrom.server.budgets.domain.BudgetAction
import run.ostrom.server.budgets.domain.BudgetDecision
import run.ostrom.server.budgets.domain.BudgetGuardImpl
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetOverride
import run.ostrom.server.budgets.domain.BudgetOverrideGrant
import run.ostrom.server.budgets.domain.BudgetOverrideNotFoundException
import run.ostrom.server.budgets.domain.BudgetOverridePage
import run.ostrom.server.budgets.domain.BudgetOverrideRules
import run.ostrom.server.budgets.domain.BudgetOverridesRepository
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.GetMyBudgetUseCaseImpl
import run.ostrom.server.budgets.domain.GrantBudgetOverrideUseCaseImpl
import run.ostrom.server.budgets.domain.GrantOutcome
import run.ostrom.server.budgets.domain.InvalidBudgetOverrideException
import run.ostrom.server.budgets.domain.ListBudgetOverridesUseCaseImpl
import run.ostrom.server.budgets.domain.NoBudgetAlerts
import run.ostrom.server.budgets.domain.OverriddenBudgetsRepository
import run.ostrom.server.budgets.domain.RevokeBudgetOverrideUseCaseImpl
import run.ostrom.server.budgets.domain.RevokeOutcome
import run.ostrom.server.budgets.domain.StaleBudgetOverrideException
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.secrets.domain.AdminRequiredException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The rules of temporary daily budgets, how they change a call's limits, and the use cases over a fake repository. */
class BudgetOverridesTest {
    private val org = TestCallers.orgA
    private val member = TestCallers.memberA.memberId
    private val admin = TestCallers.adminA

    @Test
    fun anOverrideEndsAtTheEarlierOfItsDurationAndTheNextMidnightUtc() {
        val noon = Instant.parse("2026-11-15T12:00:00Z")
        assertEquals(noon + 60.minutes, BudgetOverrideRules.expiresAt(noon, 60))
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), BudgetOverrideRules.expiresAt(noon, 1440))
    }

    @Test
    fun theBoundsRefuseAnAllowanceADurationOrAReasonOutOfRange() {
        val fine = BudgetOverrideGrant(0, 1, "ok", null)
        BudgetOverrideRules.requireGrant(fine)
        BudgetOverrideRules.requireGrant(fine.copy(micros = BudgetOverrideRules.MAX_MICROS, durationMinutes = 1440))
        for (bad in listOf(fine.copy(micros = -1), fine.copy(durationMinutes = 0), fine.copy(durationMinutes = 1441))) {
            assertFailsWith<InvalidBudgetOverrideException> { BudgetOverrideRules.requireGrant(bad) }
        }
        val tooLarge = fine.copy(micros = BudgetOverrideRules.MAX_MICROS + 1)
        assertFailsWith<InvalidBudgetOverrideException> { BudgetOverrideRules.requireGrant(tooLarge) }
        assertEquals("Support incident", BudgetOverrideRules.reason("  Support incident "))
        for (bad in listOf("", "   ", "two\nlines", "bell\u0007", "del\u007f", "x".repeat(241))) {
            val error = assertFailsWith<InvalidBudgetOverrideException> { BudgetOverrideRules.reason(bad) }
            assertEquals("invalid_budget_override", error.type)
        }
    }

    @Test
    fun anOverrideReplacesTheMembersDailyLimitThatStopsAndKeepsTheOthers() = runTest {
        val limits = InMemoryBudgetsRepository()
        val overrides = FakeOverrides()
        val repository = OverriddenBudgetsRepository(limits, overrides)
        val day = BudgetLimit(100, BudgetWindow.DAY)
        val warn = BudgetLimit(50, BudgetWindow.DAY, BudgetAction.WARN)
        val month = BudgetLimit(1_000)
        limits.replaceLimits(org, BudgetTarget.Member(member), listOf(month, day, warn), TestCallers.epoch)
        assertEquals(listOf(month, day, warn), repository.limits(org, member).member, "No override: as set")

        val active = ActiveBudgetOverride(Uuid.random(), 300, TestCallers.epoch)
        overrides.active = active
        val replaced = repository.limits(org, member)
        assertEquals(listOf(month, warn, BudgetLimit(300, BudgetWindow.DAY)), replaced.member)
        assertEquals(active, replaced.override)
        assertEquals(100, replaced.baseDailyMicros)

        limits.replaceLimits(org, BudgetTarget.Member(member), emptyList(), TestCallers.epoch)
        val added = repository.limits(org, member)
        assertEquals(listOf(BudgetLimit(300, BudgetWindow.DAY)), added.member, "Added when the member has none")
        assertNull(added.baseDailyMicros)
    }

    @Test
    fun theGuardAndTheMembersOwnBudgetSeeTheOverride() = runTest {
        val limits = InMemoryBudgetsRepository()
        val overrides = FakeOverrides()
        val repository = OverriddenBudgetsRepository(limits, overrides)
        val spend = FakeSpendReader()
        val clock = TestClock()
        val guard = BudgetGuardImpl(repository, spend, NoBudgetAlerts(), clock, RecordingLogger())
        val dayLimit = listOf(BudgetLimit(100, BudgetWindow.DAY))
        limits.replaceLimits(org, BudgetTarget.Member(member), dayLimit, TestCallers.epoch)
        spend.spent[org to member] = 80
        assertEquals(BudgetDecision.Allowed(20), guard.check(org, member))
        val active = ActiveBudgetOverride(Uuid.random(), 300, TestCallers.epoch)
        overrides.active = active
        assertEquals(BudgetDecision.Allowed(220), guard.check(org, member), "A total for the day, not an addition")
        overrides.active = active.copy(micros = 0)
        assertTrue(guard.check(org, member) is BudgetDecision.Exhausted, "Zero stops the member's calls")

        overrides.active = active
        val mine = GetMyBudgetUseCaseImpl(repository, spend, guard, clock)(TestCallers.memberA)
        assertEquals(active, mine.override)
        assertEquals(100, mine.baseDailyMicros)
    }

    @Test
    fun onlyAnAdministratorReadsAKnownMembersOverrides() = runTest {
        val overrides = FakeOverrides()
        val list = ListBudgetOverridesUseCaseImpl(overrides, Logins)
        assertFailsWith<AdminRequiredException> { list(TestCallers.memberA, "member", null) }
        assertFailsWith<BudgetOverrideNotFoundException> { list(admin, "nobody", null) }
        assertFailsWith<InvalidBudgetOverrideException> { list(admin, "member", "not-an-id") }
        overrides.pageMissing = true
        assertFailsWith<InvalidBudgetOverrideException> { list(admin, "member", Uuid.random().toString()) }
        overrides.pageMissing = false
        val granted = sample()
        overrides.page = BudgetOverridePage(listOf(granted), granted.id, null)
        val page = list(admin, "member", null)
        assertEquals(listOf(granted), page.items)
        assertEquals(granted.id, page.activeId)
        assertEquals(mapOf(member to "member", admin.memberId to "admin"), page.logins)
        overrides.page = BudgetOverridePage(emptyList(), null, null)
        assertEquals(emptyMap(), list(admin, "member", null).logins, "Nothing to name, nothing asked")
    }

    @Test
    fun aGrantChecksItsBoundsAndAnswersStaleOrFullHistory() = runTest {
        val overrides = FakeOverrides()
        val grant = GrantBudgetOverrideUseCaseImpl(overrides, Logins)
        val request = BudgetOverrideGrant(300, 60, "  Incident  ", null)
        assertFailsWith<InvalidBudgetOverrideException> { grant(admin, "member", request.copy(durationMinutes = 0)) }
        assertFailsWith<InvalidBudgetOverrideException> { grant(admin, "member", request.copy(reason = " ")) }
        val previous = Uuid.random()
        overrides.grantOutcome = GrantOutcome.Granted(sample(), previous)
        val granted = grant(admin, "member", request)
        assertEquals("Incident", overrides.grants.single().reason, "The reason is trimmed")
        assertEquals(previous, granted.superseded)
        val now = Instant.parse("2026-11-15T23:30:00Z")
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), overrides.expiry(now), "Ends at midnight UTC")
        overrides.grantOutcome = GrantOutcome.Stale
        val stale = assertFailsWith<StaleBudgetOverrideException> { grant(admin, "member", request) }
        assertEquals("stale_budget_override", stale.type)
        overrides.grantOutcome = GrantOutcome.HistoryFull
        assertFailsWith<InvalidBudgetOverrideException> { grant(admin, "member", request) }
    }

    @Test
    fun aRevocationIsKeptOnceAndRefusesAnEndedOrUnknownOverride() = runTest {
        val overrides = FakeOverrides()
        val revoke = RevokeBudgetOverrideUseCaseImpl(overrides, Logins)
        val id = Uuid.random().toString()
        assertFailsWith<BudgetOverrideNotFoundException> { revoke(admin, "member", "x", "done") }
        assertFailsWith<InvalidBudgetOverrideException> { revoke(admin, "member", id, "") }
        val revoked = sample().copy(revokedAt = TestCallers.epoch, revokedBy = admin.memberId)
        overrides.revokeOutcome = RevokeOutcome.Revoked(revoked)
        assertTrue(revoke(admin, "member", id, "done").changed)
        overrides.revokeOutcome = RevokeOutcome.AlreadyRevoked(revoked)
        val again = revoke(admin, "member", id, "done")
        assertFalse(again.changed)
        assertEquals(listOf(revoked), again.items)
        overrides.revokeOutcome = RevokeOutcome.Expired
        assertFailsWith<StaleBudgetOverrideException> { revoke(admin, "member", id, "done") }
        overrides.revokeOutcome = RevokeOutcome.NotFound
        assertFailsWith<BudgetOverrideNotFoundException> { revoke(admin, "member", id, "done") }
    }

    @Test
    fun withoutTheOrgsModuleNoLoginIsKnown() = runTest {
        assertEquals(emptyMap(), NoLogins.idsOf(org, listOf("member")))
        assertEquals(emptyMap(), NoLogins.loginsOf(org, listOf(member)))
    }

    private fun sample() = BudgetOverride(
        id = Uuid.random(),
        memberId = member,
        micros = 300,
        createdAt = TestCallers.epoch,
        expiresAt = TestCallers.epoch + 60.minutes,
        reason = "Incident",
        createdBy = admin.memberId,
    )

    private object Logins : MemberLogins {
        private val ids = mapOf("member" to TestCallers.memberA.memberId, "admin" to TestCallers.adminA.memberId)

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            ids.filterKeys { it in logins }

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            ids.entries.filter { it.value in memberIds }.associate { it.value to it.key }
    }

    /** Answers what the test sets; keeps the grants and the expiry rule it was given. */
    private class FakeOverrides : BudgetOverridesRepository {
        var active: ActiveBudgetOverride? = null
        var page = BudgetOverridePage(emptyList(), null, null)
        var pageMissing = false
        var grantOutcome: GrantOutcome = GrantOutcome.Stale
        var revokeOutcome: RevokeOutcome = RevokeOutcome.NotFound
        val grants = mutableListOf<BudgetOverrideGrant>()
        var expiry: (Instant) -> Instant = { it }

        override suspend fun active(orgId: Uuid, memberId: Uuid): ActiveBudgetOverride? = active

        override suspend fun history(orgId: Uuid, memberId: Uuid, before: Uuid?): BudgetOverridePage? =
            page.takeUnless { pageMissing }

        override suspend fun grant(
            orgId: Uuid,
            memberId: Uuid,
            actor: Uuid,
            grant: BudgetOverrideGrant,
            expiry: (Instant) -> Instant,
        ): GrantOutcome {
            grants += grant
            this.expiry = expiry
            return grantOutcome
        }

        override suspend fun revoke(orgId: Uuid, memberId: Uuid, id: Uuid, actor: Uuid, reason: String) =
            revokeOutcome
    }
}
