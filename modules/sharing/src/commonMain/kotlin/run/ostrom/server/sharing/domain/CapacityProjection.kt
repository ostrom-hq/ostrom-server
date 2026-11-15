// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * A stretch of time when, at the org's current burn rate, no usable account has quota left: from [startsAt], when the
 * last one runs out, to [endsAt], when the first reset (or the end of the projection) gives some back
 * (TeamCode's `CapacityGap`).
 *
 * @property startsAt When the last account runs out.
 * @property endsAt When capacity comes back.
 */
data class CapacityGap(val startsAt: Instant, val endsAt: Instant) {
    /**
     * The share of the current burn rate that would just last until capacity comes back, within [lookahead]:
     * `(start - now) / (min(end, now + lookahead) - now)`. It shrinks as the gap gets longer or closer. The burn rate
     * is learned over the latest hour, so a gap a day or more away is graded as if it ended at the lookahead.
     *
     * @param now The time.
     * @param lookahead How far the rate is trusted.
     */
    fun affordableShare(now: Instant, lookahead: Duration = PACING_LOOKAHEAD): Double {
        val end = minOf(endsAt, now + lookahead)
        return if (end <= now) {
            1.0
        } else {
            ((startsAt - now).coerceAtLeast(Duration.ZERO) / (end - now)).coerceIn(0.0, 1.0)
        }
    }
}

/** How far the latest-hour burn rate is trusted to grade a gap ([CapacityGap.affordableShare]): one day. */
val PACING_LOOKAHEAD: Duration = 1.days

/** How far ahead the projection looks: one seven-day window, the longest a subscription resets over. */
val PROJECTION_HORIZON: Duration = 7.days

/** The length of a provider window once it resets, when its name says it (five-hour and seven-day); else null. */
fun windowPeriod(window: String): Duration? = when {
    window.endsWith("five_hour") -> FIVE_HOURS
    window.endsWith("seven_day") -> 7.days
    else -> null
}

private val FIVE_HOURS = 5.hours

/**
 * Projects the org's current burn rate over the remaining capacity of every account it can use, to find the first
 * [CapacityGap] (TeamCode's `ProjectCapacityUseCase`). The capacity is [AccountHeadroomUseCase]'s: usable accounts
 * serve now, exhausted ones count for nothing until their windows at the ceiling reset. Disconnected, paused, pending
 * and cooling accounts are in neither list, so they never count.
 *
 * The whole load goes to one account at a time, the one whose next reset comes first (its quota is lost at that reset
 * if unused), and burns each of its windows at the org's rate for that window (a share of one account's window per
 * hour, by window name). An account serves while every known window has quota left. A window that resets refills to
 * its account's ceiling and resets again one period later ([windowPeriod]). A window absent from an account is unknown
 * and never binds: unknown is not empty.
 *
 * @param horizon How far ahead it looks.
 */
class ProjectCapacityUseCase(private val horizon: Duration = PROJECTION_HORIZON) {
    /**
     * The first gap, or null when none comes within the horizon (or nothing burns).
     *
     * @param usable The accounts that serve now.
     * @param exhausted The accounts that only lack quota.
     * @param ratesPerHour The org's rate of each window, a share of one account's window per hour.
     * @param now The time.
     */
    fun firstGap(
        usable: List<AccountHeadroom>,
        exhausted: List<AccountHeadroom>,
        ratesPerHour: Map<String, Double>,
        now: Instant,
    ): CapacityGap? {
        val accounts = (usable + exhausted).map { account ->
            Projected(
                account.windows.map {
                    Window(
                        it.window,
                        account.ceiling,
                        it.remainingShare,
                        it.resetsAt?.takeIf { reset -> reset > now }?.toEpochMilliseconds(),
                        ratesPerHour[it.window]?.takeIf { rate -> rate.isFinite() && rate > 0 },
                    )
                },
            )
        }
        return Projection(accounts, now.toEpochMilliseconds(), (now + horizon).toEpochMilliseconds()).run()
    }

    private class Window(
        val name: String,
        val ceiling: Double,
        var remaining: Double,
        var resetsAt: Long?,
        val rate: Double?,
    )

    private class Projected(val windows: List<Window>) {
        val serving: Boolean get() = windows.all { it.remaining > 0 }
        val nextReset: Long get() = windows.mapNotNull { it.resetsAt }.minOrNull() ?: Long.MAX_VALUE
    }

    /** One run of the projection, in epoch milliseconds as TeamCode's. */
    private class Projection(val accounts: List<Projected>, var t: Long, val end: Long) {
        var gapStart: Long? = null

        // Each step ends at a reset or when the serving account runs out, so it is bounded by the resets before the
        // horizon.
        fun run(): CapacityGap? {
            repeat(MAX_STEPS) {
                when (val outcome = step()) {
                    Step.Continue -> Unit
                    Step.None -> return null
                    is Step.Gap -> return outcome.gap
                }
            }
            return null
        }

        private fun refill() = accounts.forEach { account ->
            account.windows.forEach { window ->
                while (window.resetsAt?.let { it <= t } == true) {
                    window.remaining = window.ceiling
                    window.resetsAt = windowPeriod(window.name)?.let { window.resetsAt!! + it.inWholeMilliseconds }
                }
            }
        }

        private fun step(): Step {
            refill()
            val nextReset = accounts.flatMap { it.windows }.mapNotNull { it.resetsAt }.minOrNull()
            // Use first the quota lost soonest: the account whose next reset comes first.
            val serving = accounts.filter { it.serving }.minByOrNull { it.nextReset }
            val start = gapStart
            return when {
                serving == null -> waitForReset(nextReset)
                start != null -> Step.Gap(gap(start, t))
                else -> burn(serving, nextReset)
            }
        }

        private fun waitForReset(nextReset: Long?): Step {
            val start = gapStart ?: t
            gapStart = start
            val next = nextReset?.takeIf { it < end } ?: return Step.Gap(gap(start, end))
            t = next
            return Step.Continue
        }

        private fun burn(serving: Projected, nextReset: Long?): Step {
            val burning = serving.windows.filter { it.rate != null }
            if (burning.isEmpty()) return Step.None
            val runsOut = burning.associateWith {
                t + (it.remaining / it.rate!! * HOUR_MILLIS).coerceAtMost((end - t + 1).toDouble()).roundToLong()
            }
            val step = minOf(runsOut.values.min(), nextReset ?: Long.MAX_VALUE, end)
            burning.forEach { window ->
                window.remaining = if (runsOut.getValue(window) <= step) {
                    0.0
                } else {
                    window.remaining - window.rate!! * (step - t) / HOUR_MILLIS
                }
            }
            t = step
            return if (t >= end) Step.None else Step.Continue
        }

        private fun gap(start: Long, until: Long) =
            CapacityGap(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(until))
    }

    private sealed interface Step {
        data object Continue : Step

        data object None : Step

        class Gap(val gap: CapacityGap) : Step
    }

    private companion object {
        const val HOUR_MILLIS = 3_600_000.0
        const val MAX_STEPS = 100_000
    }
}
