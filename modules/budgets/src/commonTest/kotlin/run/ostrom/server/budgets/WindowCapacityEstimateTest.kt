// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.EstimateWindowCapacityUseCase
import run.ostrom.server.budgets.domain.HourCost
import run.ostrom.server.budgets.domain.pricedBetween
import run.ostrom.server.sharing.domain.QuotaWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * TeamCode's `WindowCapacityEstimateTest`: a window's full capacity in API-equivalent, learned from pairs of
 * utilization readings and the priced tokens between them.
 */
class WindowCapacityEstimateTest {
    private val hourMillis = 1.hours.inWholeMilliseconds
    private val start = Instant.fromEpochMilliseconds(500_000 * hourMillis)
    private val now = start + 10.hours
    private val reset = start + 100.hours

    private fun sample(hoursIn: Int, utilization: Double, resetsAt: Instant? = reset) =
        QuotaWindow("seven_day", utilization, resetsAt, start + hoursIn.hours)

    /** 600,000 USD millionths ($0.60) settled in each hour from [start]. */
    private fun hours(count: Int, micros: Long = 600_000, unpricedAt: Int? = null) =
        (0 until count).map {
            HourCost(start.toEpochMilliseconds() / hourMillis + it, micros, unpriced = it == unpricedAt)
        }

    private fun estimate(history: List<QuotaWindow>, costs: List<HourCost>) =
        EstimateWindowCapacityUseCase().estimate("seven_day", history, costs, now)

    @Test
    fun eachRiseOfFivePointsOrMoreIsOneSample() {
        // +6 points per hour for $0.60: the full window is worth 0.6 / 0.06 = $10.
        val result = estimate(listOf(sample(0, .10), sample(1, .16), sample(2, .22), sample(3, .28)), hours(3))
        assertEquals(3, result.samples)
        assertEquals(10_000_000, result.fullWindowMicros)
    }

    @Test
    fun useOutsideOstromLowersSomeSamplesButNotTheEstimate() {
        // Every hour Ostrom settles $0.60. In three of the five hours someone else also used the subscription, so the
        // window rose 12 points instead of 6: those samples read a $5 window, the clean ones the true $10. The median
        // keeps $5 for the budget; the reported capacity, the upper quartile, keeps $10.
        val history =
            listOf(.10, .16, .28, .40, .46, .58).mapIndexed { index, utilization -> sample(index, utilization) }
        val result = estimate(history, hours(5))
        assertEquals(5, result.samples)
        assertEquals(10_000_000, result.capacityMicros)
        assertEquals(5_000_000, result.fullWindowMicros, "The budget keeps the median")
    }

    @Test
    fun anEvenNumberOfSamplesTakesTheMeanOfTheMiddleTwo() {
        // Rises of 6, 12, 6, 12 points for $0.60 each: $10, $5, $10, $5, median $7.50.
        val history = listOf(.10, .16, .28, .34, .46).mapIndexed { index, utilization -> sample(index, utilization) }
        val result = estimate(history, hours(4))
        assertEquals(4, result.samples)
        assertEquals(7_500_000, result.fullWindowMicros)
        assertEquals(10_000_000, result.capacityMicros)
    }

    @Test
    fun whenEveryPairOverlapsOutsideUseTheEstimateStaysALowerBound() {
        // +12 points per $0.60 hour throughout: the subscription was always shared, so $5 is all that can be shown.
        val result = estimate((0..3).map { sample(it, .10 + .12 * it) }, hours(3))
        assertEquals(5_000_000, result.capacityMicros)
        assertEquals(5_000_000, result.fullWindowMicros)
    }

    @Test
    fun fewerThanThreeSamplesGiveNoEstimate() {
        val result = estimate(listOf(sample(0, .10), sample(1, .16), sample(2, .22)), hours(2))
        assertEquals(2, result.samples)
        assertNull(result.fullWindowMicros)
        assertNull(result.capacityMicros, "Unknown, never guessed")
    }

    @Test
    fun smallRisesAccumulateUntilFivePoints() {
        // Two hours of +3 points make one sample of 6 points for $1.20: the full window is worth 1.2 / 0.06 = $20.
        val result = estimate((0..6).map { sample(it, .10 + .03 * it) }, hours(6))
        assertEquals(3, result.samples)
        assertEquals(20_000_000, result.fullWindowMicros)
    }

    @Test
    fun pairsNeverSpanAResetOrADecrease() {
        val other = reset + 168.hours
        val history = listOf(
            sample(0, .10), sample(1, .16, other), sample(2, .22, other), sample(3, .05, other),
            sample(4, .11, other), sample(5, .17, other), sample(6, .23, other),
        )
        // The lone reading of the first period pairs with nothing; after the decrease the anchor restarts at 5 %.
        val result = estimate(history, hours(7))
        assertEquals(4, result.samples)
        assertEquals(10_000_000, result.fullWindowMicros)
    }

    @Test
    fun readingsWithoutAKnownResetOrOutOfRangeArePairedWithNothing() {
        val unknown = listOf(sample(0, .10, null), sample(1, .16, null), sample(2, .22, null), sample(3, .28, null))
        assertEquals(0, estimate(unknown, hours(3)).samples)
        val invalid = listOf(sample(0, -.1), sample(1, 1.2), sample(2, Double.NaN))
        assertEquals(0, estimate(invalid, hours(3)).samples)
        val old = QuotaWindow("seven_day", .1, reset, now - 8.days)
        val future = QuotaWindow("seven_day", .9, reset, now + 1.hours)
        assertEquals(0, estimate(listOf(old, sample(1, .16), future), hours(3)).samples, "Outside the seven days")
    }

    @Test
    fun aPairWithUnpricedOrNoTrafficIsNoSample() {
        val history = listOf(sample(0, .10), sample(1, .16), sample(2, .22), sample(3, .28), sample(4, .34))
        // Hour 1 holds an unpriced model: the pair (1→2) is skipped. Hour 3 has no traffic at all (outside use).
        val costs = hours(4, unpricedAt = 1).filterNot { it.hourNumber == start.toEpochMilliseconds() / hourMillis + 3 }
        assertEquals(2, estimate(history, costs).samples)
    }

    @Test
    fun resetsThatJitterWithinAMinuteShareAPeriod() {
        val history = (0..3).map { sample(it, .10 + .06 * it, reset + (it * 7_000).milliseconds) }
        assertEquals(3, estimate(history, hours(3)).samples)
    }

    @Test
    fun aPairSpansAtLeastAnHour() {
        // +6 points every 20 minutes: a pair closes only once an hour has passed (18 points for $0.60: a $3.33 window).
        val history = (0..9).map { QuotaWindow("seven_day", .10 + .06 * it, reset, start + (it * 20).minutes) }
        val result = estimate(history, hours(3))
        assertEquals(3, result.samples)
        assertEquals(3_333_333, result.fullWindowMicros)
    }

    @Test
    fun partialHoursArePricedInProportion() {
        // Readings at :30: each pair covers half of two hours, so $0.60 again per six points.
        val history = (0..3).map { QuotaWindow("seven_day", .10 + .06 * it, reset, start + it.hours + 30.minutes) }
        val result = estimate(history, hours(4))
        assertEquals(3, result.samples)
        assertEquals(10_000_000, result.fullWindowMicros)
    }

    @Test
    fun nothingIsPricedBetweenAnInstantAndAnEarlierOne() {
        val costs = hours(3).associateBy { it.hourNumber }
        assertEquals(0.0, pricedBetween(costs, start + 2.hours, start + 1.hours))
        assertEquals(0.0, pricedBetween(costs, start, start))
    }
}
