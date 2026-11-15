// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.sharing.domain.AccountHeadroom
import run.ostrom.server.sharing.domain.AccountHeadroomUseCase
import run.ostrom.server.sharing.domain.PoolAccountObservation
import run.ostrom.server.sharing.domain.ProjectCapacityUseCase
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.WindowPacing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The pace the capacity controller advises (TeamCode's `Pace`). */
enum class Pace {
    /** Observations are missing or stale. */
    UNKNOWN,

    /** Within the observed capacity. */
    NORMAL,

    /** Every usable account is projected to run out before one resets: admissions are spaced. */
    SLOW_DOWN,

    /** Spare capacity even at twice the rate. */
    CAN_INCREASE,

    /** No usable account left. */
    PAUSE,
    ;

    /** The name in the API. */
    val wire: String get() = name.lowercase()
}

/**
 * The controller's advice for one scope (TeamCode's `PacingAdvice`).
 *
 * @property pace The pace.
 * @property maxConcurrent The member's concurrency limit that applies, 0 for none: pacing never lowers it.
 * @property minInterval The spacing between two admissions of the scope's calls.
 * @property message The advice in words, for the agent.
 */
data class PacingAdvice(val pace: Pace, val maxConcurrent: Int, val minInterval: Duration, val message: String)

/**
 * One advice as stored and delivered (TeamCode's `AgentDirective`).
 *
 * @property id Changes whenever the advice or its context changes.
 * @property memberId The member.
 * @property advice The advice.
 * @property createdAt When this advice was first made.
 * @property expiresAt When it stops applying, unless the controller renews it.
 */
data class AgentDirective(
    val id: String,
    val memberId: String,
    val advice: PacingAdvice,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * How hard a SLOW_DOWN spaces admissions, from the [run.ostrom.server.sharing.domain.CapacityGap]'s affordable share
 * (TeamCode's `SlowDownLevel`). Pacing never caps parallelism: each level only spaces admissions, more as the share
 * shrinks: LIGHT (at least 1/2) 2 s, MODERATE (1/4 to 1/2) 5 s, SEVERE (under 1/4) 10 s. PAUSE spaces by 30 s.
 *
 * @property minimumAffordableShare The least share of the level.
 * @property interval Its spacing.
 */
@Suppress("MagicNumber") // TeamCode's levels, stated in the documentation above.
enum class SlowDownLevel(val minimumAffordableShare: Double, val interval: Duration) {
    /** A gap far or short. */
    LIGHT(.5, 2.seconds),

    /** A gap closer or longer. */
    MODERATE(.25, 5.seconds),

    /** A gap close or long. */
    SEVERE(0.0, 10.seconds),
    ;

    /** The level of a share. */
    companion object {
        /** The level of [affordableShare]; SEVERE for a share that is not finite. */
        fun of(affordableShare: Double): SlowDownLevel =
            if (!affordableShare.isFinite()) SEVERE else entries.first { affordableShare >= it.minimumAffordableShare }
    }
}

/** The paces from the loosest to the strictest. */
private val PACE_STRICTNESS = listOf(Pace.CAN_INCREASE, Pace.NORMAL, Pace.UNKNOWN, Pace.SLOW_DOWN, Pace.PAUSE)

/** Tightening first: a higher pace, then a longer interval, then fewer permitted requests (zero is unlimited). */
val PACING_STRICTNESS: Comparator<PacingAdvice> = compareBy<PacingAdvice> {
    PACE_STRICTNESS.indexOf(it.pace)
}.thenBy { it.minInterval }.thenByDescending { if (it.maxConcurrent <= 0) Int.MAX_VALUE else it.maxConcurrent }

/** The tightest enabled limit, or 0 (unlimited) when none applies (TeamCode's `concurrencyCeiling`). */
fun concurrencyCeiling(limits: Iterable<Int>): Int = limits.filter { it > 0 }.minOrNull() ?: 0

/**
 * One account a scope may use, with its windows' forecasts (TeamCode's `AccountPacing`).
 *
 * @property account The account.
 * @property windows Its windows' latest readings and forecasts.
 */
data class AccountPacing(val account: SharedAccount, val windows: List<WindowPacing>)

/**
 * Paces one provider from the capacity of every account of it the scope can use, taken together (TeamCode's
 * `PacingUseCase`). The goal is to never run out of quota entirely: as long as some account has quota, work goes on at
 * full speed. It slows down only when, at the org's current burn rate, every usable account is projected to be empty
 * before a reset gives some capacity back, and only spaces admissions, more as the gap gets longer or closer.
 *
 * - No usable account at all: PAUSE.
 * - A gap ahead: SLOW_DOWN, graded by the gap's affordable share.
 * - No gap, but an account without a known five-hour or seven-day window, or no known rate for one of them: UNKNOWN.
 * - No gap even at twice the current rate, and every usable window under half used: CAN_INCREASE. Otherwise NORMAL.
 *
 * The org's rate for a window is the sum of the learned rates of that window over the usable accounts.
 *
 * @param headroom The remaining capacity.
 * @param projection The projection of the rate over it.
 */
class PacingUseCase(
    private val headroom: AccountHeadroomUseCase = AccountHeadroomUseCase(),
    private val projection: ProjectCapacityUseCase = ProjectCapacityUseCase(),
) {
    /**
     * The advice.
     *
     * @param accounts The accounts of the provider the scope may use.
     * @param maximum The member's concurrency limit, 0 when none applies; pacing never lowers it.
     * @param now The time.
     */
    fun advise(accounts: List<AccountPacing>, maximum: Int, now: Instant): PacingAdvice {
        require(maximum >= 0)
        val observations = accounts.map { account ->
            PoolAccountObservation(account.account, account.windows.map { it.quota })
        }
        val usable = headroom.usable(observations, now)
        return when {
            accounts.isEmpty() -> advice(Pace.UNKNOWN, maximum)
            usable.isEmpty() -> advice(Pace.PAUSE, maximum)
            else -> served(accounts, observations, usable, maximum, now)
        }
    }

    private fun served(
        accounts: List<AccountPacing>,
        observations: List<PoolAccountObservation>,
        usable: List<AccountHeadroom>,
        maximum: Int,
        now: Instant,
    ): PacingAdvice {
        val exhausted = headroom.exhausted(observations, now)
        // The load runs on the usable accounts: an exhausted account's past rate has already moved to them.
        val serving = usable.map { it.account.id }.toSet()
        val rates = accounts.filter { it.account.id in serving }.flatMap { it.windows }
            .filter { it.forecast.pointsPerHour != null }
            .groupBy { it.quota.window }
            .mapValues { (_, windows) -> windows.sumOf { it.forecast.pointsPerHour!! / PERCENT } }
        val gap = projection.firstGap(usable, exhausted, rates, now)
        val known = SHARED_WINDOWS.all { it in rates } &&
            usable.all { account -> SHARED_WINDOWS.all { account.window(it) != null } }
        return when {
            gap != null -> advice(Pace.SLOW_DOWN, maximum, SlowDownLevel.of(gap.affordableShare(now)))
            !known -> advice(Pace.UNKNOWN, maximum)
            else -> {
                val spare = usable.all { it.windows.all { window -> window.utilization < CAN_INCREASE_UTILIZATION } } &&
                    projection.firstGap(usable, exhausted, rates.mapValues { it.value * 2 }, now) == null
                advice(if (spare) Pace.CAN_INCREASE else Pace.NORMAL, maximum)
            }
        }
    }

    /** Pacing only spaces admissions; the concurrency stays the member's limit (zero is unlimited). */
    internal fun advice(pace: Pace, maximum: Int, level: SlowDownLevel = SlowDownLevel.SEVERE): PacingAdvice =
        when (pace) {
            Pace.SLOW_DOWN -> describe(pace, maximum, level.interval)
            Pace.PAUSE -> describe(pace, maximum, PAUSE_INTERVAL)
            else -> describe(pace, maximum, Duration.ZERO)
        }

    /** The same advice under a tighter concurrency ceiling (zero keeps the advice's own). */
    internal fun withCeiling(advice: PacingAdvice, ceiling: Int): PacingAdvice =
        describe(advice.pace, concurrencyCeiling(listOf(advice.maxConcurrent, ceiling)), advice.minInterval)

    /** The bounds. */
    companion object {
        /** The spacing of a PAUSE. */
        val PAUSE_INTERVAL: Duration = 30.seconds

        private val SHARED_WINDOWS = listOf("five_hour", "seven_day")

        /** CAN_INCREASE also needs every usable window under half used: a zero rate near the ceiling is no spare. */
        private const val CAN_INCREASE_UTILIZATION = .5

        private const val PERCENT = 100.0

        /** The advice's words for [pace]. */
        internal fun describe(pace: Pace, maximum: Int, interval: Duration) =
            PacingAdvice(pace, maximum, interval, message(pace, maximum, interval))

        private fun message(pace: Pace, maximum: Int, interval: Duration): String = when (pace) {
            Pace.UNKNOWN -> "Quota observations are missing or stale. Keep current useful work; do not increase " +
                "parallelism based on this signal."
            Pace.NORMAL -> "Consumption is within the observed capacity trajectory. Continue useful work within your " +
                "organization's limits."
            Pace.SLOW_DOWN -> "At the current burn rate, every usable account is projected to run out of quota " +
                "before one resets. Reduce optional work; the gateway spaces admissions by " +
                "${interval.inWholeSeconds} seconds."
            Pace.CAN_INCREASE -> "Fresh observations show spare capacity. If useful work is already queued, you may " +
                "increase parallelism" + (if (maximum > 0) " up to $maximum" else "") +
                ". Do not invent work to consume quota."
            Pace.PAUSE -> "Eligible accounts are close to their observed quota threshold. Pause optional work until " +
                "capacity recovers."
        }
    }
}

/**
 * A claimed delivery of a session's advice (TeamCode's `DirectiveDelivery`).
 *
 * @property directive The advice.
 * @property claimId The claim, which the receipt settles.
 */
data class DirectiveDelivery(val directive: AgentDirective, val claimId: String)
