// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.AutomaticDaily
import run.ostrom.server.budgets.domain.AutomaticPoolSize
import run.ostrom.server.budgets.domain.PoolContribution
import run.ostrom.server.budgets.domain.PoolSizeEstimate
import run.ostrom.server.budgets.domain.SizeSharedPoolUseCase
import run.ostrom.server.budgets.domain.WindowCapacity
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.PoolAccountObservation
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.RefreshOwnership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * TeamCode's `AutomaticPoolSizeTest`: the case table of the automatic shared pool size (ADR 0002, step 2). Capacities
 * are in API-equivalent USD millionths, which are Ostrom's budgets (no credits): the estimate is the budget. Each
 * account's ceiling is TeamCode's .95 threshold.
 */
class AutomaticPoolSizeTest {
    private val now = Instant.fromEpochMilliseconds(1_000 * 1.days.inWholeMilliseconds)

    private fun account(
        name: String,
        status: AccountStatus = AccountStatus.READY,
        ownership: RefreshOwnership = RefreshOwnership.OSTROM,
        cooldownUntil: Instant? = null,
    ) = sharedAccount(name, now, status, ownership, cooldownUntil)

    private fun quota(window: String, utilization: Double, resetsIn: Duration): QuotaWindow =
        quota(window, utilization, resetsIn, now)

    private fun capacity(fiveHour: Long?, sevenDay: Long?) = listOfNotNull(
        fiveHour?.let { WindowCapacity("five_hour", it, 3) },
        sevenDay?.let { WindowCapacity("seven_day", it, 3) },
    )

    private fun size(accounts: List<PoolAccountObservation>, capacities: Map<String, List<WindowCapacity>>) =
        SizeSharedPoolUseCase().size(accounts, capacities.mapKeys { accountId(it.key) }, now)

    private fun PoolSizeEstimate.byName(name: String) = accounts.single { it.accountId == accountId(name) }

    /** Weekly 25 % used (70 points left), reset in 3.5 days: 0.70 × 200M / 3.5 = 40M a day; five-hour 45.6M. */
    private val plusAccount = observed(
        account("plus"),
        quota("five_hour", .2, 2.hours),
        quota("seven_day", .25, 3.days + 12.hours),
    )
    private val plusCapacity = "plus" to capacity(10_000_000, 200_000_000)

    @Test
    fun noAccountsGiveNoAutomaticValue() {
        val estimate = size(emptyList(), emptyMap())
        assertEquals(PoolSizeEstimate.NO_ACCOUNTS, estimate.reason, "Without an account the typed budget applies")
        assertEquals(0, estimate.dailyMicros)
    }

    @Test
    fun oneAccountGivesTheSmallerOfItsWeeklyAndFiveHourDailyCapacity() {
        val estimate = size(listOf(plusAccount), mapOf(plusCapacity))
        val share = estimate.accounts.single()
        assertEquals(PoolContribution.COUNTED, share.status)
        assertEquals("seven_day", share.binding)
        assertEquals(40_000_000, share.dailyMicros)
        assertEquals(40_000_000, estimate.dailyMicros, "USD millionths are the budget's unit")
        assertNull(estimate.reason)
    }

    @Test
    fun severalAccountsWithDifferentPlansAndWindowsAddUp() {
        // Max-like: weekly 50 % used, reset in 2 days: 0.45 × 1000M / 2 = 225M; five-hour 50M × 0.95 × 4.8 = 228M.
        val max = observed(account("max"), quota("five_hour", .7, 1.hours), quota("seven_day", .5, 2.days))
        // Plenty of weekly left (0.90 × 400M / 6 = 60M): the five-hour rate binds, 10M × 0.95 × 4.8 = 45.6M.
        val fresh = observed(account("fresh"), quota("five_hour", .0, 5.hours), quota("seven_day", .05, 6.days))
        val estimate = size(
            listOf(plusAccount, max, fresh),
            mapOf(
                plusCapacity,
                "max" to capacity(50_000_000, 1_000_000_000),
                "fresh" to capacity(10_000_000, 400_000_000),
            ),
        )
        assertEquals(
            mapOf(
                accountId("plus") to 40_000_000L,
                accountId("max") to 225_000_000L,
                accountId("fresh") to 45_600_000L,
            ),
            estimate.accounts.associate { it.accountId to it.dailyMicros },
        )
        assertEquals("five_hour", estimate.byName("fresh").binding)
        assertEquals(310_600_000, estimate.dailyMicros)
    }

    @Test
    fun exhaustedAndUnavailableAccountsAddNothing() {
        val accounts = listOf(
            plusAccount,
            observed(account("weekly-full"), quota("five_hour", .1, 1.hours), quota("seven_day", .96, 3.days)),
            observed(
                account("reconnect", AccountStatus.RECONNECT_REQUIRED),
                quota("five_hour", .1, 1.hours),
                quota("seven_day", .1, 3.days),
            ),
            observed(account("disabled", AccountStatus.DISABLED), quota("five_hour", .1, 1.hours)),
            observed(account("cooling", cooldownUntil = now + 1.minutes), quota("five_hour", .1, 1.hours)),
            observed(account("pending", ownership = RefreshOwnership.PENDING_HANDOVER)),
        )
        val others = listOf("weekly-full", "reconnect", "disabled", "cooling", "pending")
        val estimate = size(accounts, mapOf(plusCapacity) + others.associateWith { capacity(10_000_000, 200_000_000) })
        val full = estimate.byName("weekly-full")
        assertEquals(0, full.dailyMicros, "A weekly window at the ceiling has nothing left until its reset")
        assertEquals(PoolContribution.COUNTED, full.status)
        for (name in listOf("reconnect", "disabled", "cooling", "pending")) {
            assertEquals(PoolContribution.UNAVAILABLE, estimate.byName(name).status, name)
            assertEquals(0, estimate.byName(name).dailyMicros, name)
        }
        assertEquals(40_000_000, estimate.dailyMicros, "Only the usable account counts")
    }

    @Test
    fun aFiveHourWindowAtTheCeilingStillCountsItsSteadyRate() {
        // Excluded from admission until its five-hour reset, but the day ahead holds several five-hour windows.
        val busy =
            observed(account("busy"), quota("five_hour", .97, 1.hours), quota("seven_day", .25, 3.days + 12.hours))
        val share = size(listOf(busy), mapOf("busy" to capacity(10_000_000, 200_000_000))).accounts.single()
        assertEquals(PoolContribution.COUNTED, share.status)
        assertEquals(40_000_000, share.dailyMicros)
    }

    @Test
    fun aWeeklyWindowResettingWithinTheDayCountsWhatComesBack() {
        // Full now, reset in 6 hours: the 18 hours after it hold 0.95 × 200M × 18 / 168 = 20,357,143.
        val full = observed(account("full"), quota("five_hour", .1, 1.hours), quota("seven_day", .96, 6.hours))
        val share = size(listOf(full), mapOf("full" to capacity(10_000_000, 200_000_000))).accounts.single()
        assertEquals("seven_day", share.binding)
        assertEquals(20_357_143, share.dailyMicros)
    }

    @Test
    fun aWeeklyWindowWithAnUnknownResetIsSpreadOverTheWeek() {
        val unknown = observed(
            account("unknown"),
            quota("five_hour", .1, 1.hours),
            QuotaWindow("seven_day", .3, null, now),
        )
        // 0.65 × 200M / 7 = 18,571,429, below the five-hour term.
        val share = size(listOf(unknown), mapOf("unknown" to capacity(10_000_000, 200_000_000))).accounts.single()
        assertEquals(18_571_429, share.dailyMicros)
    }

    @Test
    fun accountsWithoutAnEstimateAreLeftOutAndAnUnobservedWindowDoesNotBind() {
        val unestimated = observed(account("new"), quota("five_hour", .1, 1.hours), quota("seven_day", .1, 3.days))
        val stale = observed(account("stale"), quota("five_hour", .1, -1.hours))
        val weeklyOnly = observed(account("half"), quota("five_hour", .1, 1.hours), quota("seven_day", .1, 3.days))
        // A provider that reports no weekly window: only the five-hour rate applies (10M × 0.95 × 4.8 = 45.6M).
        val fiveHourOnly = observed(account("codex"), quota("five_hour", .1, 1.hours))
        val estimate = size(
            listOf(unestimated, stale, weeklyOnly, fiveHourOnly),
            mapOf(
                "stale" to capacity(10_000_000, 200_000_000),
                "half" to capacity(null, 200_000_000),
                "codex" to capacity(10_000_000, null),
            ),
        )
        assertEquals(PoolContribution.NO_ESTIMATE, estimate.byName("new").status)
        assertEquals(
            PoolContribution.NO_FRESH_OBSERVATION,
            estimate.byName("stale").status,
            "A past reset says nothing",
        )
        assertEquals(PoolContribution.NO_ESTIMATE, estimate.byName("half").status, "An unestimated window could bind")
        assertEquals(PoolContribution.COUNTED, estimate.byName("codex").status)
        assertEquals(45_600_000, estimate.byName("codex").dailyMicros)
        assertEquals(0, estimate.byName("new").dailyMicros)
        assertNull(estimate.reason, "Accounts without a figure are left out; the others still size the pool")
        assertEquals(45_600_000, estimate.dailyMicros, "Only the estimated account counts")
        val none = size(
            listOf(unestimated, stale, weeklyOnly),
            mapOf("stale" to capacity(10_000_000, 200_000_000), "half" to capacity(null, 200_000_000)),
        )
        assertEquals(
            PoolSizeEstimate.NO_ESTIMATE,
            none.reason,
            "When no account has an estimate the typed budget applies",
        )
    }

    @Test
    fun aZeroSumWithAnAccountLeftOutIsNoEstimate() {
        // The only counted account is at its weekly ceiling (0 until its reset), the other one is still learning: a
        // stored 0 would refuse everyone while the new account can serve.
        val weeklyFull =
            observed(account("weekly-full"), quota("five_hour", .1, 1.hours), quota("seven_day", .96, 3.days))
        val learning = observed(account("new"), quota("five_hour", .1, 1.hours), quota("seven_day", .1, 3.days))
        val capacities = mapOf("weekly-full" to capacity(10_000_000, 200_000_000))
        assertEquals(PoolSizeEstimate.NO_ESTIMATE, size(listOf(weeklyFull, learning), capacities).reason)
        val alone = size(listOf(weeklyFull), capacities)
        assertNull(alone.reason, "Without an account left out, zero is a real value")
        assertEquals(0, alone.dailyMicros)
    }

    @Test
    fun aLearnedWindowWithoutAFreshReadingLeavesTheAccountOut() {
        // The weekly reading went stale at its reset: sizing on the five-hour rate alone would inflate the account.
        val reset = observed(account("reset"), quota("five_hour", .1, 1.hours), quota("seven_day", .5, (-1).minutes))
        val estimate =
            size(listOf(reset, plusAccount), mapOf("reset" to capacity(10_000_000, 200_000_000), plusCapacity))
        assertEquals(PoolContribution.NO_FRESH_OBSERVATION, estimate.accounts.first().status)
    }

    @Test
    fun noEstimateAtAllGivesNoAutomaticValue() {
        val unestimated = observed(account("new"), quota("five_hour", .1, 1.hours))
        assertEquals(PoolSizeEstimate.NO_ESTIMATE, size(listOf(unestimated), emptyMap()).reason)
    }

    @Test
    fun theSettingOffKeepsTheTypedBudgetAndSwitchingOnAndOffMovesOnlyTheTotal() {
        val manual = 1_000_000L
        val automatic = AutomaticDaily(80_000_000L, now - 10.minutes)
        assertEquals(manual, AutomaticPoolSize.effectiveBudget(manual, automatic, false, now), "Off: the typed budget")
        assertEquals(
            80_000_000L,
            AutomaticPoolSize.effectiveBudget(manual, automatic, true, now),
            "On: the computed value",
        )
        assertEquals(
            manual,
            AutomaticPoolSize.effectiveBudget(manual, null, true, now),
            "On without any computed value",
        )
        assertNull(AutomaticPoolSize.effectiveBudget(null, null, true, now), "No typed budget and no value: none")
        val stale = automatic.copy(computedAt = now - 1.hours - 1.seconds)
        assertEquals(manual, AutomaticPoolSize.effectiveBudget(manual, stale, true, now), "Older than an hour: stale")
        val future = automatic.copy(computedAt = now + 1.minutes)
        assertEquals(manual, AutomaticPoolSize.effectiveBudget(manual, future, true, now), "A value from the future")
        val zero = automatic.copy(micros = 0)
        assertEquals(0L, AutomaticPoolSize.effectiveBudget(manual, zero, true, now), "Zero is real")
        assertTrue(AutomaticPoolSize.usesAutomatic(automatic, true, now))
        assertFalse(AutomaticPoolSize.usesAutomatic(automatic, false, now))
        assertFalse(AutomaticPoolSize.usesAutomatic(null, true, now))
    }

    @Test
    fun aNewValueMovesAtMostTwentyPercentPerHour() {
        fun smooth(previous: Long?, ago: Duration, target: Long) =
            AutomaticPoolSize.smooth(previous?.let { AutomaticDaily(it, now - ago) }, target, now)
        assertEquals(100, smooth(null, Duration.ZERO, 100), "The first value is the estimate")
        assertEquals(120, smooth(100, 1.hours, 1_000))
        assertEquals(80, smooth(100, 1.hours, 10))
        assertEquals(103, smooth(100, 10.minutes, 1_000), "Ten minutes allow a sixth of the hourly step")
        assertEquals(1_100, smooth(1_000, 30.minutes, 2_000), "Half an hour allows half the step")
        assertEquals(102, smooth(100, 10.minutes, 102), "Within the step the estimate applies as is")
        assertEquals(120, smooth(100, 90.minutes, 1_000), "Past an hour the step stays an hour's")
        assertEquals(5_000, smooth(100, 3.hours, 5_000), "A value older than two hours restarts from the estimate")
        assertEquals(5_000, smooth(100, (-1).minutes, 5_000), "A value from the future is no reference")
        assertEquals(1_000, smooth(0, 1.hours, 1_000), "Nothing scales from zero: the pool grows back after a reset")
    }
}
