// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.sharing.domain.BuildForecastSeriesUseCase
import run.ostrom.server.sharing.domain.ForecastHistoryUseCase
import run.ostrom.server.sharing.domain.ForecastQuotaUseCase
import run.ostrom.server.sharing.domain.MAX_QUOTA_OBSERVATION_AGE
import run.ostrom.server.sharing.domain.QuotaForecast
import run.ostrom.server.sharing.domain.QuotaObservation
import run.ostrom.server.sharing.domain.QuotaWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * TeamCode's forecasts (`ForecastContinuityTest`, `UnknownResetTest`, and `CapacityTest`'s forecast cases), on
 * instants: a rate is learned from a continuous recent segment only, an unknown reset never promises headroom, and a
 * stale or future reading stays stale.
 */
class ForecastTest {
    private val now = Instant.fromEpochMilliseconds(2_000_000)

    private fun sample(age: Duration, utilization: Double, reset: Instant? = now + 1.hours) =
        QuotaWindow("five_hour", utilization, reset, now - age)

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)

    @Test
    fun aFreshReadingAfterAnUnobservedGapCannotInheritALearnedBurnRate() {
        val value = ForecastHistoryUseCase().evaluate(
            listOf(sample(10.minutes, .1), sample(9.minutes, .2), sample(Duration.ZERO, .3)),
            now,
        )!!
        assertEquals(QuotaForecast.State.UNKNOWN, value.forecast.state)
        assertNull(value.forecast.pointsPerHour)
        assertNull(value.forecast.reachesThresholdAt)
    }

    @Test
    fun aContinuousRecentSegmentLearnsWithoutAveragingAcrossTheEarlierGap() {
        val value = ForecastHistoryUseCase().evaluate(
            listOf(
                sample(15.minutes, .1),
                sample(14.minutes, .2),
                sample(2.minutes, .3),
                sample(1.minutes, .4),
                sample(Duration.ZERO, .5),
            ),
            now,
        )!!
        assertEquals(600.0, assertNotNull(value.forecast.pointsPerHour), .00001)
        assertEquals(QuotaForecast.State.EXHAUSTS_FIRST, value.forecast.state)
    }

    @Test
    fun aLowerReadingStartsANewLearningSegmentWithoutPoisoningTheNextHour() {
        val value = ForecastHistoryUseCase().evaluate(
            listOf(sample(3.minutes, .8), sample(2.minutes, .1), sample(1.minutes, .2), sample(Duration.ZERO, .3)),
            now,
        )!!
        assertEquals(600.0, assertNotNull(value.forecast.pointsPerHour), .00001)
        assertEquals(QuotaForecast.State.EXHAUSTS_FIRST, value.forecast.state)
    }

    @Test
    fun chartSegmentsBreakOnResetGapAndDecreaseAndIgnoreFutureSamples() {
        val later = now + 2.hours
        val values = listOf(
            sample(15.minutes, .1),
            sample(14.minutes, .2),
            sample(8.minutes, .3),
            sample(7.minutes, .1),
            sample(6.minutes, .2, later),
            sample(5.minutes, .3, later),
            sample((-1).milliseconds, .9),
            // Readings of another window, out of range or out of 0..1 are no part of the curve.
            QuotaWindow("seven_day", .5, later, now - 10.minutes),
            sample(2.hours, .1),
            sample(4.minutes, Double.NaN),
            sample(4.minutes, 1.5),
        )
        assertEquals(listOf(2, 1, 1, 2), ForecastHistoryUseCase().segments(values, now).map { it.size })
        assertEquals(emptyList(), ForecastHistoryUseCase().segments(emptyList(), now))
    }

    @Test
    fun twoReadingsAtTheSameInstantNeverShareASegment() {
        val segments = ForecastHistoryUseCase().segments(listOf(sample(1.minutes, .1), sample(1.minutes, .1)), now)
        assertEquals(listOf(1, 1), segments.map { it.size })
    }

    @Test
    fun snapshotsKeepFutureAndStaleCurrentReadingsConservative() {
        val builder = BuildForecastSeriesUseCase()
        val history = listOf(sample(2.minutes, .1), sample(1.minutes, .2), sample(Duration.ZERO, .3))
        val future = builder(sample((-1).milliseconds, .4), history, now)
        assertEquals(QuotaForecast.State.STALE, future.forecast.state)
        assertNull(future.forecast.reachesThresholdAt)
        val stale = builder(sample(3_700_000.milliseconds, .3), emptyList(), now)
        assertEquals(QuotaForecast.State.STALE, stale.forecast.state)
        assertTrue(stale.segments.isEmpty())
        assertEquals(0, stale.learningSamples)
        assertEquals(Duration.ZERO, stale.learningSpan)
    }

    @Test
    fun aSeriesLearnsFromItsOwnWindowsEarlierReadingsOnly() {
        val builder = BuildForecastSeriesUseCase(.98)
        val history = listOf(
            sample(1.minutes, .2),
            sample(2.minutes, .1),
            QuotaWindow("seven_day", .9, now + 1.hours, now - 1.minutes),
            // A reading after the current one is not history.
            sample((-1).minutes, .9),
        )
        val series = builder(sample(Duration.ZERO, .3), history, now)
        assertEquals(QuotaForecast.State.EXHAUSTS_FIRST, series.forecast.state)
        assertEquals(600.0, series.forecast.pointsPerHour!!, .00001)
        assertEquals(3, series.learningSamples)
        assertEquals(2.minutes, series.learningSpan)
    }

    @Test
    fun unknownResetNeverPromisesHeadroomButFreshExhaustionRemainsActionable() {
        val history = ForecastHistoryUseCase()
        fun window(used: Double) = history.evaluate(
            (0..2).map { index -> QuotaWindow("five_hour", used, null, now - 2.minutes + index.minutes) },
            now,
        )!!
        val spare = window(.1)
        assertEquals(QuotaForecast.State.UNKNOWN, spare.forecast.state)
        assertNull(spare.forecast.reachesThresholdAt)
        assertNull(spare.forecast.pointsPerHour)
        val full = window(.99)
        assertEquals(QuotaForecast.State.EXHAUSTED, full.forecast.state)
        assertNull(full.forecast.pointsPerHour)
        val stale = history.evaluate(listOf(full.quota), now + MAX_QUOTA_OBSERVATION_AGE + 1.milliseconds)!!
        assertEquals(QuotaForecast.State.STALE, stale.forecast.state)
        // Read more than an hour ago: no segment left to learn from, and stale.
        val old = history.evaluate(listOf(full.quota), now + 2.hours)!!
        assertEquals(QuotaForecast.State.STALE, old.forecast.state)
        assertNull(history.evaluate(listOf(full.quota), now - 1.hours))
    }

    @Test
    fun aRateNeedsThreeReadingsOverAMinute() {
        val history = ForecastHistoryUseCase()
        val two = history.evaluate(listOf(sample(1.minutes, .1), sample(Duration.ZERO, .2)), now)!!
        assertEquals(QuotaForecast.State.UNKNOWN, two.forecast.state)
        val brief = history.evaluate(
            listOf(sample(40_000.milliseconds, .1), sample(20_000.milliseconds, .2), sample(Duration.ZERO, .3)),
            now,
        )!!
        assertEquals(QuotaForecast.State.UNKNOWN, brief.forecast.state)
    }

    @Test
    fun freshBurnProjectsFromObservationInsteadOfCurrentTime() {
        val observation = QuotaObservation(0.5, at(100_000), at(200_000_000))
        val forecast = ForecastQuotaUseCase().forecast(observation, 0.01, at(150_000), 0.98)
        assertEquals(at(172_900_000), forecast.reachesThresholdAt)
        assertEquals(QuotaForecast.State.EXHAUSTS_FIRST, forecast.state)
        assertEquals(1.0, forecast.pointsPerHour!!, 1e-9)
    }

    @Test
    fun unknownStaleZeroAndExhaustedCapacityRemainDistinct() {
        val useCase = ForecastQuotaUseCase()
        val observation = QuotaObservation(0.5, at(1000), at(10_000_000))
        assertEquals(QuotaForecast.State.UNKNOWN, useCase.forecast(null, null, at(1000)).state)
        assertEquals(QuotaForecast.State.IDLE, useCase.forecast(observation, 0.0, at(1000)).state)
        assertEquals(0.0, useCase.forecast(observation, 0.0, at(1000)).pointsPerHour)
        assertEquals(QuotaForecast.State.STALE, useCase.forecast(observation, 0.1, at(400_000)).state)
        assertEquals(QuotaForecast.State.STALE, useCase.forecast(observation, 0.1, at(0)).state)
        assertEquals(QuotaForecast.State.STALE, useCase.forecast(observation, 0.1, at(10_000_000)).state)
        // A fresh reading whose reset has passed no longer describes the window.
        val passed = observation.copy(resetsAt = at(2000))
        assertEquals(QuotaForecast.State.STALE, useCase.forecast(passed, 0.1, at(3000)).state)
        val exhausted = useCase.forecast(observation.copy(utilization = 0.95), 0.1, at(1000))
        assertEquals(QuotaForecast.State.EXHAUSTED, exhausted.state)
        assertEquals(at(1000), exhausted.reachesThresholdAt)
        assertEquals(10.0, exhausted.pointsPerHour!!, 1e-9)
        assertEquals(QuotaForecast.State.RESET_FIRST, useCase.forecast(observation, 0.01, at(1000)).state)
    }

    @Test
    fun aReadingOutOfRangeOrARateThatIsNoRateIsUnknown() {
        val useCase = ForecastQuotaUseCase()
        val observation = QuotaObservation(0.5, at(1000), at(10_000_000))
        for (utilization in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            val reading = observation.copy(utilization = utilization)
            assertEquals(QuotaForecast.State.UNKNOWN, useCase.forecast(reading, .1, at(1000)).state)
        }
        for (rate in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -0.1)) {
            assertEquals(QuotaForecast.State.UNKNOWN, useCase.forecast(observation, rate, at(1000)).state)
        }
        val noReset = observation.copy(resetsAt = null)
        assertEquals(QuotaForecast.State.UNKNOWN, useCase.forecast(noReset, .1, at(1000)).state)
    }

    @Test
    fun aRateTooSlowToMatterNeverOverflowsTheInstant() {
        val observation = QuotaObservation(0.1, at(1000), at(10_000_000))
        val forecast = ForecastQuotaUseCase().forecast(observation, 1e-300, at(1000))
        assertEquals(Instant.DISTANT_FUTURE, forecast.reachesThresholdAt)
        assertEquals(QuotaForecast.State.RESET_FIRST, forecast.state)
    }

    @Test
    fun aThresholdOutOfRangeIsAProgrammingError() {
        val observation = QuotaObservation(0.5, at(1000), at(10_000_000))
        for (threshold in listOf(Double.NaN, 0.0, -1.0, 1.5)) {
            assertFailsWith<IllegalArgumentException> {
                ForecastQuotaUseCase().forecast(observation, .1, at(1000), threshold)
            }
        }
        val full = ForecastQuotaUseCase().forecast(observation.copy(utilization = 1.0), .1, at(1000), 1.0)
        assertEquals(QuotaForecast.State.EXHAUSTED, full.state)
    }

    @Test
    fun theStatesAreLowerCaseOnTheWire() {
        assertEquals(
            listOf("unknown", "stale", "exhausted", "idle", "reset_first", "exhausts_first"),
            QuotaForecast.State.entries.map { it.wire },
        )
    }
}
