// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

/**
 * How long a reading describes its window for a forecast (TeamCode's `MAX_QUOTA_OBSERVATION_AGE_MILLIS`): older, the
 * window is `stale`; two readings further apart never share a segment.
 */
val MAX_QUOTA_OBSERVATION_AGE: Duration = 5.minutes

/** The history a forecast reads and draws: the last hour (TeamCode's Forecasts page). */
val FORECAST_HISTORY: Duration = 1.hours

/**
 * One reading of one account's window; percentages of different plans are never summed.
 *
 * @property utilization Used share, 0..1.
 * @property observedAt When it was read.
 * @property resetsAt When the window resets, if the provider said.
 */
data class QuotaObservation(val utilization: Double, val observedAt: Instant, val resetsAt: Instant?)

/**
 * What a window's reading and learned rate say about when it reaches the pressure threshold.
 *
 * @property reachesThresholdAt When the window reaches the threshold at the learned rate, if known.
 * @property pointsPerHour The learned rate in percentage points per hour, null while unknown (0 is a known idle rate).
 * @property state What is known.
 */
data class QuotaForecast(val reachesThresholdAt: Instant?, val pointsPerHour: Double?, val state: State) {
    /** What a forecast knows; an unknown state is said, never drawn as 0 %. */
    enum class State {
        /** No reading, no reset known, or no rate learned yet. */
        UNKNOWN,

        /** The reading is older than [MAX_QUOTA_OBSERVATION_AGE], in the future, or its reset has passed. */
        STALE,

        /** At or above the threshold. */
        EXHAUSTED,

        /** A learned rate of zero. */
        IDLE,

        /** The window resets before it reaches the threshold. */
        RESET_FIRST,

        /** The window reaches the threshold before it resets. */
        EXHAUSTS_FIRST,
        ;

        /** The name in the API. */
        val wire: String get() = name.lowercase()
    }
}

/**
 * Forecasts one window from its latest reading and a learned rate (TeamCode's `ForecastQuotaUseCase`).
 *
 * @param maximumAge How long a reading describes its window.
 */
class ForecastQuotaUseCase(private val maximumAge: Duration = MAX_QUOTA_OBSERVATION_AGE) {
    /**
     * The forecast.
     *
     * @param observation The latest reading, or null.
     * @param ratePerHour The learned rate in share (0..1) per hour, or null when none was learned.
     * @param now The time.
     * @param threshold The share at which the account stops taking requests, in (0, 1].
     *
     * @return The forecast: never an ETA without a known reset.
     */
    fun forecast(
        observation: QuotaObservation?,
        ratePerHour: Double?,
        now: Instant,
        threshold: Double = AccountLimits.DEFAULT_CEILING,
    ): QuotaForecast {
        require(threshold.isFinite() && threshold > 0 && threshold <= 1)
        return when {
            observation == null || !observation.utilization.isFinite() || observation.utilization !in 0.0..1.0 ->
                QuotaForecast(null, null, QuotaForecast.State.UNKNOWN)
            observation.isStaleAt(now) -> QuotaForecast(null, null, QuotaForecast.State.STALE)
            else -> {
                val validRate = ratePerHour?.takeIf { observation.resetsAt != null && it.isFinite() && it >= 0 }
                fresh(observation, validRate, now, threshold)
            }
        }
    }

    /** A reading in the future, whose reset has passed, or older than [maximumAge] no longer describes its window. */
    private fun QuotaObservation.isStaleAt(now: Instant): Boolean =
        observedAt > now || resetsAt?.let { it <= now } == true || now - observedAt > maximumAge

    /** The forecast of a fresh reading at [validRate], a rate known to be finite and not negative, or null. */
    private fun fresh(
        observation: QuotaObservation,
        validRate: Double?,
        now: Instant,
        threshold: Double,
    ): QuotaForecast {
        val reset = observation.resetsAt
        return when {
            observation.utilization >= threshold ->
                QuotaForecast(now, validRate?.times(PERCENT), QuotaForecast.State.EXHAUSTED)
            // A missing reset can hide a reset between readings: never promise spare capacity or an ETA.
            reset == null || validRate == null -> QuotaForecast(null, null, QuotaForecast.State.UNKNOWN)
            validRate == 0.0 -> QuotaForecast(null, 0.0, QuotaForecast.State.IDLE)
            else -> projected(observation, reset, validRate, threshold)
        }
    }

    /** When the window reaches [threshold] at [rate] (positive), and whether that comes before [reset]. */
    private fun projected(
        observation: QuotaObservation,
        reset: Instant,
        rate: Double,
        threshold: Double,
    ): QuotaForecast {
        val duration = ((threshold - observation.utilization) / rate).toDuration(DurationUnit.HOURS)
        val eta = if (duration >= Instant.DISTANT_FUTURE - observation.observedAt) {
            Instant.DISTANT_FUTURE
        } else {
            observation.observedAt + duration
        }
        val state = if (eta < reset) QuotaForecast.State.EXHAUSTS_FIRST else QuotaForecast.State.RESET_FIRST
        return QuotaForecast(eta, rate * PERCENT, state)
    }

    private companion object {
        const val PERCENT = 100.0
    }
}

/**
 * A window's latest reading and its forecast.
 *
 * @property quota The latest reading.
 * @property forecast Its forecast.
 */
data class WindowPacing(val quota: QuotaWindow, val forecast: QuotaForecast)

/**
 * Learns a window's rate from its recent readings (TeamCode's `ForecastHistoryUseCase`).
 *
 * @param threshold The share at which the account stops taking requests (its `utilizationCeiling`).
 */
class ForecastHistoryUseCase(private val threshold: Double = AccountLimits.DEFAULT_CEILING) {
    /**
     * The readings of the last hour of the latest reading's window, oldest first, cut into segments: a segment never
     * joins two readings across a reset, a drop in utilization or more than [MAX_QUOTA_OBSERVATION_AGE] apart. The
     * curves and the rate share these boundaries.
     *
     * @param history Readings of one account.
     * @param now The time: later readings are left out.
     *
     * @return The segments, oldest first.
     */
    fun segments(history: List<QuotaWindow>, now: Instant): List<List<QuotaWindow>> {
        val sorted = history
            // A NaN is in no range.
            .filter { it.observedAt in (now - FORECAST_HISTORY)..now && it.utilization in 0.0..1.0 }
            .sortedBy { it.observedAt }
        val latest = sorted.lastOrNull() ?: return emptyList()
        val output = mutableListOf<MutableList<QuotaWindow>>()
        sorted.filter { it.window == latest.window }.forEach { sample ->
            val previous = output.lastOrNull()?.last()
            if (previous == null || sample.continues(previous).not()) output.add(mutableListOf())
            output.last().add(sample)
        }
        return output
    }

    /**
     * The latest reading's forecast at the rate learned from its latest segment: at least three readings over at least
     * a minute, never across a drop, and only with a known reset.
     *
     * @param history Readings of one window.
     * @param now The time.
     *
     * @return The forecast, or null without any reading up to [now].
     */
    fun evaluate(history: List<QuotaWindow>, now: Instant): WindowPacing? {
        val sorted = history.filter { it.observedAt <= now }.sortedBy { it.observedAt }
        val latest = sorted.lastOrNull() ?: return null
        return evaluate(sorted, latest, now)
    }

    /** [evaluate] of readings already up to [now] and sorted, [latest] the last of them. */
    internal fun evaluate(sorted: List<QuotaWindow>, latest: QuotaWindow, now: Instant): WindowPacing {
        val sameWindow = segments(sorted, now).lastOrNull().orEmpty()
        var rate: Double? = null
        if (latest.resetsAt != null && sameWindow.size >= MIN_SAMPLES &&
            sameWindow.last().observedAt - sameWindow.first().observedAt >= MIN_SPAN
        ) {
            var weighted = 0.0
            var weight = Duration.ZERO
            sameWindow.zipWithNext().forEach { (a, b) ->
                weighted += b.utilization - a.utilization
                weight += b.observedAt - a.observedAt
            }
            rate = weighted / weight.toDouble(DurationUnit.HOURS)
        }
        val observation = QuotaObservation(latest.utilization, latest.observedAt, latest.resetsAt)
        return WindowPacing(latest, ForecastQuotaUseCase().forecast(observation, rate, now, threshold))
    }

    /**
     * Whether this reading continues [previous]'s segment: the same reset, no drop, and later by at most
     * [MAX_QUOTA_OBSERVATION_AGE]. Within a segment the readings only rise and time only moves on, so the rate needs no
     * other check (TeamCode's monotonic test).
     */
    private fun QuotaWindow.continues(previous: QuotaWindow): Boolean {
        val elapsed = observedAt - previous.observedAt
        return resetsAt == previous.resetsAt && utilization >= previous.utilization &&
            elapsed > Duration.ZERO && elapsed <= MAX_QUOTA_OBSERVATION_AGE
    }
}

/** Readings a rate needs at least. */
private const val MIN_SAMPLES = 3

/** The span a rate needs at least. */
private val MIN_SPAN: Duration = 1.minutes
