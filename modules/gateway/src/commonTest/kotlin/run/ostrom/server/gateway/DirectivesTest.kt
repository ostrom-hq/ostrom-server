// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.AgentDirective
import run.ostrom.server.gateway.domain.DeliveryStage
import run.ostrom.server.gateway.domain.DirectiveDelivery
import run.ostrom.server.gateway.domain.DirectiveRepository
import run.ostrom.server.gateway.domain.DirectiveUseCasesImpl
import run.ostrom.server.gateway.domain.DirectiveWrite
import run.ostrom.server.gateway.domain.GatewaySettings
import run.ostrom.server.gateway.domain.GetPacingSettingsUseCaseImpl
import run.ostrom.server.gateway.domain.InvalidDirectiveRequestException
import run.ostrom.server.gateway.domain.Pace
import run.ostrom.server.gateway.domain.PacingAdvice
import run.ostrom.server.gateway.domain.PacingSettings
import run.ostrom.server.gateway.domain.PacingSettingsRepository
import run.ostrom.server.gateway.domain.PacingSources
import run.ostrom.server.gateway.domain.RunPacingControllerUseCase
import run.ostrom.server.gateway.domain.SetPacingSettingsUseCaseImpl
import run.ostrom.server.gateway.domain.SpendDecision
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.gateway.domain.SpendRequest
import run.ostrom.server.gateway.domain.StoredDirective
import run.ostrom.server.gateway.domain.UpdateDirectivesUseCase
import run.ostrom.server.gateway.domain.UpdateDirectivesUseCaseImpl
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.ActiveSession
import run.ostrom.server.sharing.domain.NoAccountGroups
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.SharingOrgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The stored advice in memory, as the controller and the delivery use it. */
class InMemoryDirectives : DirectiveRepository {
    val rows = mutableMapOf<Pair<Uuid, String>, StoredDirective>()
    val claims = mutableListOf<String>()
    val receipts = mutableListOf<String>()

    override suspend fun stored(orgId: Uuid) = rows.toMap()

    override suspend fun save(orgId: Uuid, writes: List<DirectiveWrite>) {
        writes.forEach { rows[it.memberId to it.scopeKey] = StoredDirective(it.directive, it.contextKey, it.recovery) }
    }

    override suspend fun current(orgId: Uuid, memberId: Uuid, session: String?): AgentDirective? =
        rows[memberId to session.orEmpty()]?.directive

    override suspend fun claim(
        orgId: Uuid,
        memberId: Uuid,
        session: String,
        stage: DeliveryStage,
        tokenId: String,
    ): DirectiveDelivery? = current(orgId, memberId, session)?.let {
        claims += "$session:${stage.wire}:$tokenId"
        DirectiveDelivery(it, "claim")
    }

    override suspend fun acknowledge(
        orgId: Uuid,
        memberId: Uuid,
        id: String,
        stage: DeliveryStage,
        session: String?,
        claimId: String?,
        tokenId: String,
    ) {
        receipts += "$id:${stage.wire}:$session:$claimId"
    }
}

/** The settings in memory. */
class InMemoryPacingSettings : PacingSettingsRepository {
    var settings = PacingSettings()

    override suspend fun get(orgId: Uuid) = settings

    override suspend fun update(orgId: Uuid, observationOnly: Boolean?, autoPoolSize: Boolean?) =
        settings.let { before ->
            settings = PacingSettings(observationOnly ?: before.observationOnly, autoPoolSize ?: before.autoPoolSize)
            before to settings
        }
}

/** TeamCode's `RouteDirectivePersistenceTest` and `PacingObservationPersistenceTest` cases the use cases decide. */
class DirectivesTest {
    private val clock = TestClock()
    private val accounts = InMemoryAccounts()
    private val slots = InMemorySlots()
    private val settings = InMemorySettings()
    private val directives = InMemoryDirectives()
    private val readings = mutableMapOf<Uuid, List<QuotaWindow>>()
    private var available = true
    private val history = object : QuotaHistoryRepository {
        override suspend fun record(orgId: Uuid, accountId: Uuid, windows: List<QuotaWindow>) = Unit

        override suspend fun recent(
            orgId: Uuid,
            accountId: Uuid,
            windows: List<String>,
            observed: ClosedRange<Instant>,
            limit: Int,
        ) = emptyList<QuotaWindow>()

        override suspend fun recentOfOrg(orgId: Uuid, observed: ClosedRange<Instant>, limit: Int) = readings.toMap()

        override suspend fun prune(before: Instant, limit: Int) = 0L
    }
    private val spend = object : SpendGuard {
        override suspend fun check(request: SpendRequest) = SpendDecision.Allowed

        override suspend fun available(orgId: Uuid, memberId: Uuid) = available
    }
    private val update = UpdateDirectivesUseCaseImpl(
        PacingSources(accounts, history, slots, NoAccountGroups, settings, spend),
        directives,
        clock,
    )
    private val member = Gw.memberA.memberId

    /** Nine readings, one every 30 s up to now, rising by [perMinute] and ending at [utilization]. */
    private fun rising(utilization: Double, perMinute: Double, window: String, reset: Instant): List<QuotaWindow> =
        (0..8).map { step ->
            QuotaWindow(window, utilization - perMinute * (8 - step) / 2, reset, clock.now - (30 * (8 - step)).seconds)
        }

    @Test
    fun membersWithoutARecentSessionGetNoAdvice() = runTest {
        update(Gw.orgA)
        assertTrue(directives.rows.isEmpty())
    }

    @Test
    fun theControllerAdvisesTheMemberAndEachSessionOnTheirProviders() = runTest {
        val account = accounts.share(AccountProvider.CLAUDE, "a", clock)
        val reset = clock.now + 1.hours
        readings[account.id] = rising(.3, 0.0, "five_hour", reset) + rising(.1, 0.0, "seven_day", clock.now + 5.hours)
        slots.sessions += ActiveSession(member, "s1", account.id)
        update(Gw.orgA)
        val wide = directives.rows.getValue(member to "")
        assertEquals(Pace.CAN_INCREASE, wide.directive.advice.pace)
        assertEquals(Pace.CAN_INCREASE, directives.rows.getValue(member to "s1").directive.advice.pace)
        assertEquals(clock.now + DirectiveRepository.EXPIRY, wide.directive.expiresAt)
        // The same advice in the same context keeps its id and creation.
        clock.advance(1.minutes)
        update(Gw.orgA)
        assertEquals(wide.directive.id, directives.rows.getValue(member to "").directive.id)
        assertEquals(wide.directive.createdAt, directives.rows.getValue(member to "").directive.createdAt)
        // A member limit is another context: a new id.
        settings.settings[Gw.orgA] = GatewaySettings(memberConcurrency = mapOf(member.toString() to 3))
        update(Gw.orgA)
        val limited = directives.rows.getValue(member to "").directive
        assertNotEquals(wide.directive.id, limited.id)
        assertEquals(3, limited.advice.maxConcurrent)
    }

    @Test
    fun anUnavailableBudgetOrNoSharedAccountsChangeTheAdvice() = runTest {
        val account = accounts.share(AccountProvider.CLAUDE, "a", clock)
        slots.sessions += ActiveSession(member, "s1", account.id)
        available = false
        update(Gw.orgA)
        assertEquals(Pace.PAUSE, directives.rows.getValue(member to "").directive.advice.pace)
        available = true
        settings.settings[Gw.orgA] = GatewaySettings(useSharedAccounts = false)
        update(Gw.orgA)
        assertEquals(Pace.UNKNOWN, directives.rows.getValue(member to "s1").directive.advice.pace)
    }

    @Test
    fun anAccountNamingOtherMembersIsNotTheirs() = runTest {
        val other = Gw.otherA.memberId
        val mine = accounts.share(AccountProvider.CLAUDE, "a", clock)
        accounts.update(mine.copy(limits = AccountLimits(allowedMembers = setOf(other))))
        val open = accounts.share(AccountProvider.CODEX, "b", clock)
        readings[mine.id] = listOf(QuotaWindow("five_hour", .99, clock.now + 1.hours, clock.now))
        readings[open.id] = rising(.3, 0.0, "five_hour", clock.now + 1.hours) +
            rising(.1, 0.0, "seven_day", clock.now + 5.hours)
        slots.sessions += ActiveSession(member, "s1", mine.id)
        slots.sessions += ActiveSession(member, "s2", open.id)
        update(Gw.orgA)
        // The exhausted account names another member: it never paces this one's session, which has no capacity known.
        assertEquals(Pace.UNKNOWN, directives.rows.getValue(member to "s1").directive.advice.pace)
        assertEquals(Pace.CAN_INCREASE, directives.rows.getValue(member to "s2").directive.advice.pace)
        assertEquals(Pace.UNKNOWN, directives.rows.getValue(member to "").directive.advice.pace)
    }

    @Test
    fun aLooserAdviceWaitsForStabilization() = runTest {
        val account = accounts.share(AccountProvider.CLAUDE, "a", clock)
        slots.sessions += ActiveSession(member, "s1", account.id)
        readings[account.id] = listOf(QuotaWindow("five_hour", .99, clock.now + 1.hours, clock.now))
        update(Gw.orgA)
        assertEquals(Pace.PAUSE, directives.rows.getValue(member to "").directive.advice.pace)
        readings[account.id] = rising(.3, 0.0, "five_hour", clock.now + 1.hours) +
            rising(.1, 0.0, "seven_day", clock.now + 5.hours)
        clock.advance(30.seconds)
        readings[account.id] = rising(.3, 0.0, "five_hour", clock.now + 1.hours) +
            rising(.1, 0.0, "seven_day", clock.now + 5.hours)
        update(Gw.orgA)
        val held = directives.rows.getValue(member to "")
        assertEquals(Pace.PAUSE, held.directive.advice.pace)
        assertEquals(Pace.CAN_INCREASE, held.recovery!!.pace)
    }

    @Test
    fun thePassGoesOverEveryOrgAndThrowsItsFirstFailureAtTheEnd() = runTest {
        val seen = mutableListOf<Uuid>()
        val failing = UpdateDirectivesUseCase { org ->
            seen += org
            if (org == Gw.orgA) error("down")
        }
        val orgs = SharingOrgs { listOf(Gw.orgA, Gw.orgB) }
        assertFailsWith<IllegalStateException> { RunPacingControllerUseCase(orgs, failing)() }
        assertEquals(listOf(Gw.orgA, Gw.orgB), seen)
        RunPacingControllerUseCase(SharingOrgs { listOf(Gw.orgB) }, failing)()
        val cancelled = UpdateDirectivesUseCase { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { RunPacingControllerUseCase(orgs, cancelled)() }
    }

    @Test
    fun deliveryIsSilentInObservationModeAndChecksItsInput() = runTest {
        val pacing = InMemoryPacingSettings()
        val use = DirectiveUseCasesImpl(directives, pacing)
        val directive = AgentDirective(
            "d1", member.toString(),
            PacingAdvice(Pace.NORMAL, 0, Duration.ZERO, "ok"),
            clock.now, clock.now + 3.minutes,
        )
        directives.rows[member to ""] = StoredDirective(directive, "k", null)
        directives.rows[member to "s1"] = StoredDirective(directive, "k", null)
        assertEquals(directive, use.current(Gw.memberA, null))
        assertEquals(directive, use.current(Gw.memberA, "s1"))
        assertEquals("claim", use.claim(Gw.memberA, "s1", DeliveryStage.RECEIVED_BY_CLI, "t")!!.claimId)
        use.acknowledge(Gw.memberA, "d1", DeliveryStage.EMITTED_TO_HOOK, "s1", "claim", "t")
        use.acknowledge(Gw.memberA, "d1", DeliveryStage.EMITTED_TO_HOOK, null, null, "t")
        assertEquals(listOf("d1:emitted_to_hook:s1:claim", "d1:emitted_to_hook:null:null"), directives.receipts)
        val invalid = assertFailsWith<InvalidDirectiveRequestException> {
            use.acknowledge(Gw.memberA, "d1", DeliveryStage.EMITTED_TO_HOOK, "s1", null, "t")
        }
        assertEquals("invalid_claim", invalid.type)
        assertEquals("invalid_session", assertFailsWith<InvalidDirectiveRequestException> { use.current(Gw.memberA, "a b") }.type)
        assertEquals("invalid_stage", assertFailsWith<InvalidDirectiveRequestException> { DeliveryStage.named("x") }.type)
        assertEquals(DeliveryStage.EMITTED_TO_HOOK, DeliveryStage.named("emitted_to_hook"))
        pacing.settings = PacingSettings(observationOnly = true)
        assertNull(use.current(Gw.memberA, null))
        assertNull(use.claim(Gw.memberA, "s1", DeliveryStage.RECEIVED_BY_CLI, "t"))
        assertEquals(directive, use.preview(Gw.memberA).directive, "The preview shows the advice whatever the mode")
        assertEquals(PacingSettings(observationOnly = true), use.preview(Gw.memberA).settings)
    }

    @Test
    fun onlyAdministratorsChangeTheSettingsAndAnOmittedFieldKeepsItsValue() = runTest {
        val pacing = InMemoryPacingSettings()
        val set = SetPacingSettingsUseCaseImpl(pacing)
        assertFailsWith<AdminRequiredException> { set(Gw.memberA, true, null) }
        assertEquals(PacingSettings() to PacingSettings(observationOnly = true), set(Gw.adminA, true, null))
        assertEquals(PacingSettings(true, true), set(Gw.adminA, null, true).second)
        assertEquals(PacingSettings(true, true), GetPacingSettingsUseCaseImpl(pacing)(Gw.memberA))
    }
}
