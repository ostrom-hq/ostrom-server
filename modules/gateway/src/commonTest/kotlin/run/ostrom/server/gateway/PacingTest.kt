// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.AccountPacing
import run.ostrom.server.gateway.domain.PACING_STRICTNESS
import run.ostrom.server.gateway.domain.Pace
import run.ostrom.server.gateway.domain.PacingAdvice
import run.ostrom.server.gateway.domain.PacingHysteresisUseCase
import run.ostrom.server.gateway.domain.PacingMemory
import run.ostrom.server.gateway.domain.PacingRecovery
import run.ostrom.server.gateway.domain.PacingSettings
import run.ostrom.server.gateway.domain.PacingUseCase
import run.ostrom.server.gateway.domain.RoutePacingUseCase
import run.ostrom.server.gateway.domain.SlowDownLevel
import run.ostrom.server.gateway.domain.concurrencyCeiling
import run.ostrom.server.gateway.domain.pacingAuditEvents
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.QuotaForecast
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.WindowPacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TeamCode's `ProgressivePacingTest`, `RoutePacingTest` and the hysteresis cases, on Ostrom's accounts. */
class PacingTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val reset = now + 1.hours
    private val controller = PacingUseCase()

    /** One account, both windows; the five-hour one burns at [rate] per hour from [utilization]. */
    private fun account(
        utilization: Double = .5,
        rate: Double = 0.0,
        provider: AccountProvider = AccountProvider.CLAUDE,
        sevenDay: Boolean = true,
        status: AccountStatus = AccountStatus.READY,
    ): AccountPacing {
        val shared = SharedAccount(
            Uuid.random(), Uuid.random(), provider, "a", null, "acct", null, 0, status, AccountLimits(), null, now,
        )
        val state = if (rate == 0.0) QuotaForecast.State.IDLE else QuotaForecast.State.RESET_FIRST
        val windows = listOfNotNull(
            WindowPacing(QuotaWindow("five_hour", utilization, reset, now), QuotaForecast(null, rate * 100, state)),
            WindowPacing(
                QuotaWindow("seven_day", .1, now + 1.days, now),
                QuotaForecast(null, 0.0, QuotaForecast.State.IDLE),
            ).takeIf { sevenDay },
        )
        return AccountPacing(shared, windows)
    }

    @Test
    fun pacingNeverLowersTheConcurrencyLimit() {
        assertEquals(2, controller.advise(listOf(account(rate = 5.0)), 2, now).maxConcurrent)
        val advice = (1..100).map { controller.advise(listOf(account(rate = it / 10.0)), 0, now) }
        advice.zipWithNext().forEach { (wider, narrower) -> assertTrue(PACING_STRICTNESS.compare(narrower, wider) >= 0) }
        val slow = advice.filter { it.pace == Pace.SLOW_DOWN }
        assertTrue(slow.all { it.maxConcurrent == 0 })
        assertEquals(setOf(2.seconds, 5.seconds, 10.seconds), slow.map { it.minInterval }.toSet())
        val pause = controller.advise(listOf(account(utilization = .95)), 0, now)
        assertEquals(Triple(Pace.PAUSE, 0, 30.seconds), Triple(pause.pace, pause.maxConcurrent, pause.minInterval))
        assertEquals(4, controller.advise(listOf(account(utilization = .95)), 4, now).maxConcurrent)
        val route = RoutePacingUseCase().advise(setOf(AccountProvider.CLAUDE), emptyList(), 0, now, allowanceAvailable = false)
        assertEquals(Triple(Pace.PAUSE, 0, 30.seconds), Triple(route.pace, route.maxConcurrent, route.minInterval))
        assertFailsWith<IllegalArgumentException> { controller.advise(emptyList(), -1, now) }
    }

    @Test
    fun routesWithoutAMemberLimitHaveNoConcurrencyCeiling() {
        val route = RoutePacingUseCase()
        val claude = setOf(AccountProvider.CLAUDE)
        val idle = route.advise(claude, List(3) { account(utilization = .3) }, 0, now)
        assertEquals(Triple(Pace.CAN_INCREASE, 0, Duration.ZERO), Triple(idle.pace, idle.maxConcurrent, idle.minInterval))
        assertTrue("up to" !in idle.message)
        assertTrue("up to 3" in route.advise(claude, List(3) { account(utilization = .3) }, 3, now).message)
        // Three accounts with 0.25 left each last 0.75 / 1.0 = 45 minutes before a reset in one hour: a light slowdown.
        val slow = route.advise(claude, List(3) { account(utilization = .7, rate = 1.0 / 3) }, 0, now)
        assertEquals(Triple(Pace.SLOW_DOWN, 0, 2.seconds), Triple(slow.pace, slow.maxConcurrent, slow.minInterval))
        assertTrue("2 seconds" in slow.message)
        assertEquals(2, route.advise(claude, List(3) { account(utilization = .7, rate = 1.0 / 3) }, 2, now).maxConcurrent)
    }

    @Test
    fun missingWindowsOrRatesAreUnknownAndOtherProvidersDoNotCount() {
        val route = RoutePacingUseCase()
        assertEquals(Pace.UNKNOWN, route.advise(emptySet(), listOf(account()), 0, now).pace)
        assertEquals(Pace.UNKNOWN, route.advise(setOf(AccountProvider.CODEX), listOf(account()), 0, now).pace)
        assertEquals(Pace.UNKNOWN, controller.advise(listOf(account(sevenDay = false)), 0, now).pace)
        assertEquals(Pace.PAUSE, controller.advise(listOf(account(status = AccountStatus.DISABLED)), 0, now).pace)
        // Above half used, no gap even at twice the rate is not enough for CAN_INCREASE.
        assertEquals(Pace.NORMAL, controller.advise(listOf(account(utilization = .6)), 0, now).pace)
        val unknownRate = account().let { it.copy(windows = it.windows.map { w -> w.copy(forecast = QuotaForecast(null, null, QuotaForecast.State.UNKNOWN)) }) }
        assertEquals(Pace.UNKNOWN, controller.advise(listOf(unknownRate), 0, now).pace)
        // One provider idle never hides another one's pressure.
        val mixed = route.advise(
            setOf(AccountProvider.CLAUDE, AccountProvider.CODEX),
            listOf(account(utilization = .3), account(utilization = .95, provider = AccountProvider.CODEX)),
            0,
            now,
        )
        assertEquals(Pace.PAUSE, mixed.pace)
    }

    @Test
    fun slowDownLevelsFollowTheAffordableShare() {
        assertEquals(SlowDownLevel.LIGHT, SlowDownLevel.of(.5))
        assertEquals(SlowDownLevel.MODERATE, SlowDownLevel.of(.3))
        assertEquals(SlowDownLevel.SEVERE, SlowDownLevel.of(.1))
        assertEquals(SlowDownLevel.SEVERE, SlowDownLevel.of(Double.NaN))
        assertEquals(0, concurrencyCeiling(listOf(0, 0)))
        assertEquals(3, concurrencyCeiling(listOf(0, 5, 3)))
        assertEquals("slow_down", Pace.SLOW_DOWN.wire)
    }

    @Test
    fun tighterAdviceAppliesAtOnceAndLooserAdviceMustStayStable() {
        val hysteresis = PacingHysteresisUseCase()
        val normal = PacingAdvice(Pace.NORMAL, 0, Duration.ZERO, "")
        val slow = PacingAdvice(Pace.SLOW_DOWN, 0, 5.seconds, "")
        val lighter = PacingAdvice(Pace.SLOW_DOWN, 0, 2.seconds, "")
        val unknown = PacingAdvice(Pace.UNKNOWN, 0, Duration.ZERO, "")
        val expires = now + 3.minutes
        assertEquals(slow, hysteresis.decide(null, slow, now).advice)
        assertEquals(slow, hysteresis.decide(PacingMemory(normal, expires, null), slow, now).advice)
        assertEquals(normal, hysteresis.decide(PacingMemory(slow, now, null), normal, now).advice, "Expired: at once")
        assertEquals(unknown, hysteresis.decide(PacingMemory(slow, expires, null), unknown, now).advice)
        val held = hysteresis.decide(PacingMemory(slow, expires, null), lighter, now)
        assertEquals(slow, held.advice)
        assertEquals(PacingRecovery(Pace.SLOW_DOWN, now, 2.seconds), held.recovery)
        val later = now + 2.minutes
        assertEquals(lighter, hysteresis.decide(PacingMemory(slow, later + 1.minutes, held.recovery), lighter, later).advice)
        val switched = hysteresis.decide(PacingMemory(slow, later + 1.minutes, held.recovery), normal, later)
        assertEquals(slow, switched.advice, "Another candidate starts its own stabilization")
        assertEquals(later, switched.recovery!!.startedAt)
        val future = PacingRecovery(Pace.SLOW_DOWN, later, 2.seconds)
        assertEquals(now, hysteresis.decide(PacingMemory(slow, expires, future), lighter, now).recovery!!.startedAt)
        assertEquals(slow, hysteresis.decide(PacingMemory(slow, expires, null), slow, now).advice)
    }

    @Test
    fun settingsChangesNameTheirAuditEvents() {
        val off = PacingSettings()
        assertEquals(emptyList(), pacingAuditEvents(off, off))
        assertEquals(
            listOf("pacing.observation_enabled", "pool_size.automatic_enabled"),
            pacingAuditEvents(off, PacingSettings(observationOnly = true, autoPoolSize = true)),
        )
        assertEquals(
            listOf("pacing.enforcement_enabled", "pool_size.manual_enabled"),
            pacingAuditEvents(PacingSettings(observationOnly = true, autoPoolSize = true), off),
        )
    }
}
