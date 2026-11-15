// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.AccountPoolShare
import run.ostrom.server.budgets.domain.EstimateWindowCapacityUseCase
import run.ostrom.server.budgets.domain.HourCost
import run.ostrom.server.budgets.domain.PoolContribution
import run.ostrom.server.budgets.domain.PoolSizeEstimate
import run.ostrom.server.budgets.domain.PoolSizeReport
import run.ostrom.server.budgets.domain.PricedCost
import run.ostrom.server.budgets.domain.PricedTokens
import run.ostrom.server.budgets.domain.SizeSharedPoolUseCase
import run.ostrom.server.budgets.domain.SplitWindowUsageUseCase
import run.ostrom.server.budgets.domain.TokenMix
import run.ostrom.server.budgets.domain.WindowCapacity
import run.ostrom.server.budgets.domain.WindowUsageSplit
import run.ostrom.server.budgets.domain.allTokens
import run.ostrom.server.budgets.domain.dailyCapacity
import run.ostrom.server.gateway.domain.ModelPrice
import run.ostrom.server.gateway.domain.PriceTable
import run.ostrom.server.gateway.domain.TokenUsage
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.QuotaWindow
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * TeamCode's `SubscriptionCapacityTest`: the capacity of the subscriptions, read from the provider's percentages and
 * calibrated on Ostrom's own traffic: the full daily capacity of each account, the use outside Ostrom, unknown accounts
 * and the conversion into tokens.
 */
class SubscriptionCapacityTest {
    private val hourMillis = 1.hours.inWholeMilliseconds
    private val now = Instant.fromEpochMilliseconds(1_000 * 1.days.inWholeMilliseconds) + 10.hours
    private val weekStart = now - 58.hours
    private val weekReset = weekStart + 7.days

    /** [micros] settled in each hour from [from] to [now]. */
    private fun hours(from: Instant, micros: Long) =
        (from.toEpochMilliseconds() / hourMillis until now.toEpochMilliseconds() / hourMillis)
            .map { HourCost(it, micros) }

    private fun reading(utilization: Double, at: Instant = now, resetsAt: Instant? = weekReset) =
        QuotaWindow("seven_day", utilization, resetsAt, at)

    @Test
    fun theWindowIsSplitBetweenOstromAndOutsideUse() {
        // A $1,000 weekly window. Ostrom settled $5 an hour for the 58 hours since it opened: $290, 29 points. The
        // provider reads 40 %: 11 points, $110, were used outside Ostrom, and 60 % ($600) is left.
        val history = listOf(reading(.30, now - 5.hours), reading(.40))
        val split = SplitWindowUsageUseCase()
            .split("seven_day", history, hours(weekStart, 5_000_000), 1_000_000_000, now)
        assertNotNull(split)
        assertEquals(weekStart, split.since, "The window opened one period before its reset")
        assertEquals(.40, split.observedShare)
        assertEquals(290_000_000, split.ostromMicros)
        assertEquals(.29, assertNotNull(split.ostromShare), 1e-9)
        assertEquals(.11, assertNotNull(split.outsideShare), 1e-9)
        assertEquals(110_000_000, split.outsideMicros)
        assertEquals(600_000_000, split.remainingMicros)
    }

    @Test
    fun ostromAloneLeavesNoOutsideUse() {
        // Ostrom explains more than the reading (whole points, hourly buckets): outside use is zero, never negative.
        val split = SplitWindowUsageUseCase()
            .split("seven_day", listOf(reading(.28)), hours(weekStart, 5_000_000), 1_000_000_000, now)
        assertEquals(0.0, split?.outsideShare)
        assertEquals(0, split?.outsideMicros)
    }

    @Test
    fun withoutACapacityOrAPriceTheSplitIsUnknown() {
        val history = listOf(reading(.40))
        val splitter = SplitWindowUsageUseCase()
        val unlearned = assertNotNull(splitter.split("seven_day", history, hours(weekStart, 5_000_000), null, now))
        assertEquals(.40, unlearned.observedShare, "The provider's reading is still shown")
        assertEquals(290_000_000, unlearned.ostromMicros)
        assertNull(unlearned.ostromShare)
        assertNull(unlearned.outsideShare)
        assertNull(unlearned.remainingMicros)
        val zero = splitter.split("seven_day", history, emptyList(), 0, now)
        assertNull(zero?.remainingMicros, "A zero capacity is none")
        val unpriced = hours(weekStart, 5_000_000).mapIndexed { index, cost ->
            if (index == 3) cost.copy(unpriced = true) else cost
        }
        val split = assertNotNull(splitter.split("seven_day", history, unpriced, 1_000_000_000, now))
        assertNull(split.ostromMicros, "An unpriced model in the window makes Ostrom's part unknown")
        assertNull(split.outsideShare)
        assertNull(split.outsideMicros)
        assertEquals(600_000_000, split.remainingMicros, "What is left only needs the capacity and the reading")
    }

    @Test
    fun anAccountWithoutAReadingOfACurrentWindowHasNoSplit() {
        val splitter = SplitWindowUsageUseCase()
        assertNull(splitter.split("seven_day", emptyList(), emptyList(), 1_000_000_000, now))
        val past = reading(.4, now - 1.days, now - 1.hours)
        assertNull(splitter.split("seven_day", listOf(past), emptyList(), 1_000_000_000, now), "That window has reset")
        assertNull(splitter.split("seven_day", listOf(reading(.4, resetsAt = null)), emptyList(), 1, now), "No reset")
        assertNull(splitter.split("seven_day", listOf(reading(.4, now + 1.hours)), emptyList(), 1, now), "Not read yet")
        assertNull(splitter.split("seven_day", listOf(reading(1.4)), emptyList(), 1, now), "Out of range")
        assertNull(splitter.split("model:opus", listOf(reading(.4)), emptyList(), 1, now), "A window of unknown length")
    }

    @Test
    fun calibrationAndSplitAgreeOnTheSameReadings() {
        // Ostrom spends $12 an hour and the provider rises 1.2 points an hour from it: a $1,000 window. From hour 35
        // someone else adds 1.2 points an hour. The clean pairs calibrate $1,000; the split then attributes the extra
        // 24 points to outside use.
        val readings = (0..55 step 5).map { h ->
            reading(.012 * h + if (h > 35) .012 * (h - 35) else 0.0, weekStart + h.hours)
        }
        val costs = hours(weekStart, 12_000_000)
        val capacity = EstimateWindowCapacityUseCase().estimate("seven_day", readings, costs, now)
        assertEquals(11, capacity.samples)
        assertEquals(1_000_000_000, capacity.capacityMicros, "The upper quartile keeps the clean pairs")
        val split =
            assertNotNull(SplitWindowUsageUseCase().split("seven_day", readings, costs, capacity.capacityMicros, now))
        assertEquals(.66, assertNotNull(split.ostromShare), 1e-9)
        assertEquals(.24, assertNotNull(split.outsideShare), 1e-9)
        assertEquals(240_000_000, split.outsideMicros)
    }

    private fun learned(fiveHour: Long?, sevenDay: Long?) = listOf(
        WindowCapacity("five_hour", fiveHour, if (fiveHour == null) 1 else 5),
        WindowCapacity("seven_day", sevenDay, if (sevenDay == null) 0 else 4),
    )

    private fun windows(name: String, weekly: Double, status: AccountStatus = AccountStatus.READY) = observed(
        sharedAccount(name, now, status),
        quota("five_hour", .1, 1.hours, now),
        quota("seven_day", weekly, 5.days, now),
    )

    @Test
    fun capacityIsTheFullDailyRateWhateverIsUsedAndUnknownAccountsAreCountedApart() {
        // Max-like A: five-hour $300 (→ $1,440 a day), weekly $2,100 (→ $300 a day): $300 a day, whatever it used.
        // B is at 99 % of its week: today it has almost nothing left, but its capacity is the same $300 a day. C has no
        // learned weekly window: unknown. D is paused: not part of the capacity.
        val accounts = listOf(
            windows("a", .2),
            windows("b", .99),
            windows("c", .3),
            windows("d", .3, AccountStatus.DISABLED),
        )
        val capacities = mapOf(
            "a" to learned(300_000_000, 2_100_000_000), "b" to learned(300_000_000, 2_100_000_000),
            "c" to learned(300_000_000, null), "d" to learned(300_000_000, 2_100_000_000),
        ).mapKeys { accountId(it.key) }
        val estimate = SizeSharedPoolUseCase().size(accounts, capacities, now)
        val byId = estimate.accounts.associateBy { it.accountId }
        assertEquals(300_000_000, byId.getValue(accountId("a")).capacityDailyMicros)
        assertEquals(300_000_000, byId.getValue(accountId("b")).capacityDailyMicros)
        assertEquals(0, byId.getValue(accountId("b")).dailyMicros, "Nothing left before its reset")
        assertNull(byId.getValue(accountId("c")).capacityDailyMicros, "Unknown, not guessed")
        assertEquals(300_000_000, byId.getValue(accountId("d")).capacityDailyMicros, "Still reported per account")
        assertEquals(600_000_000, estimate.capacityDailyMicros, "a + b; the paused account cannot serve")
        assertEquals(1, estimate.capacityUnknownAccounts, "c can serve but is not learned: the total is a floor")
        // What is left today is much smaller than the capacity: a's 75 weekly points over 5 days.
        assertEquals((0.75 * 2_100_000_000 / 5).toLong(), byId.getValue(accountId("a")).dailyMicros)
    }

    @Test
    fun aFiveHourOnlyAccountUsesItsFiveHourRateAndNoWindowMeansUnknown() {
        val fiveHourOnly = learned(300_000_000, null).associateBy { it.window }
        assertEquals(1_440_000_000, dailyCapacity(listOf("five_hour"), fiveHourOnly))
        assertNull(dailyCapacity(emptyList(), learned(300_000_000, 2_100_000_000).associateBy { it.window }))
        assertNull(dailyCapacity(listOf("five_hour", "seven_day"), emptyMap()))
        assertEquals(
            300_000_000,
            dailyCapacity(
                listOf("five_hour", "seven_day", "model:fable"),
                learned(300_000_000, 2_100_000_000).associateBy { it.window },
            ),
            "Model windows do not size the shared capacity",
        )
    }

    @Test
    fun theMixPricesCacheApartAndConvertsCapacityIntoTokens() {
        val opus = ModelPrice("anthropic", "claude-opus-5-5", 4_000_000, 5_000_000, 200_000, 20_000_000)
        val prices = PriceTable(listOf(opus))
        // 1M input ($4), 1M cache writes ($5), 100M cache reads ($20), 1M output ($20): $49 for 103M tokens, half of
        // it through a shared account (`claude`) and half through the org's key (`anthropic`), one model.
        val half = TokenUsage(input = 500_000, output = 500_000, cacheRead = 50_000_000, cacheWrite = 500_000)
        val usage = listOf(
            PricedTokens("claude", "claude-opus-5-5", 1, half),
            PricedTokens("anthropic", "claude-opus-5-5", 2, half),
            PricedTokens("codex", "gpt-5", 2, TokenUsage(input = 1_000_000)),
            PricedTokens(null, "mystery", 2, TokenUsage(output = 10)),
        )
        val mix = TokenMix.of(prices, usage)
        val priced = mix.models.first()
        assertEquals("anthropic", priced.provider, "Grouped by the price table's family")
        assertEquals(PricedCost(4_000_000L, 5_000_000L, 20_000_000L, 20_000_000L), priced.cost)
        assertEquals(49_000_000L, priced.cost?.totalMicros)
        assertEquals(103_000_000L, priced.tokens.allTokens)
        val codex = mix.models.single { it.model == "gpt-5" }
        assertEquals("openai", codex.provider)
        assertNull(codex.cost, "An unpriced model has no cost, and no part in the conversion")
        assertEquals(1_000_000L, codex.unpricedTokens)
        assertEquals("", mix.models.single { it.model == "mystery" }.provider, "No provider recorded")
        assertEquals(listOf("/mystery", "openai/gpt-5"), mix.unpricedModels)
        assertEquals(103.0 / 49, assertNotNull(mix.tokensPerMicro), 1e-9)
        val report = PoolSizeReport(
            false, null, null, null, false,
            PoolSizeEstimate(0, emptyList(), null, capacityDailyMicros = 300_000_000),
            emptyList(),
            emptyMap(),
            mix = mix,
        )
        assertEquals((300_000_000 * 103.0 / 49).roundToLong(), report.capacityDailyTokens)
        val unpriced = report.copy(mix = TokenMix.of(PriceTable(emptyList()), usage))
        assertNull(unpriced.capacityDailyTokens, "Without prices, no conversion")
        assertNull(unpriced.mix.tokensPerMicro)
    }

    @Test
    fun outsideUseAddsUpOverTheWeeklyWindowsThatKnowIt() {
        fun split(window: String, outside: Long?) =
            WindowUsageSplit(window, now - 1.days, now, weekReset, .5, 1, .1, outside?.let { .1 }, outside, 1)
        val shares = listOf("a", "b", "c", "d").map { AccountPoolShare(accountId(it), PoolContribution.COUNTED, 0) }
        val report = PoolSizeReport(
            false, null, null, null, false, PoolSizeEstimate(0, shares, null), emptyList(), emptyMap(),
            usage = mapOf(
                accountId("a") to listOf(split("five_hour", 7), split("seven_day", 100)),
                accountId("b") to listOf(split("seven_day", 50)),
                accountId("c") to listOf(split("seven_day", null)),
            ),
        )
        assertEquals(150L, report.outsideWeeklyMicros, "Weekly windows only; unknown ones are left out")
        assertEquals(2, report.outsideUnknownAccounts, "c is not learned and d has no reading: the sum is a floor")
        assertNull(report.copy(usage = mapOf(accountId("c") to listOf(split("seven_day", null)))).outsideWeeklyMicros)
    }
}
