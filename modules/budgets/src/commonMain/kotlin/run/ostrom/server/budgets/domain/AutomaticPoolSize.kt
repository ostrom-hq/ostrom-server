// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.sharing.domain.AccountHeadroom
import run.ostrom.server.sharing.domain.AccountHeadroomUseCase
import run.ostrom.server.sharing.domain.PoolAccountObservation
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.WindowHeadroom
import run.ostrom.server.sharing.domain.windowPeriod
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

// TeamCode's automatic shared pool size (ADR 0002 step 2, `AutomaticPoolSize.kt`). Ostrom has no credits: budgets are
// already USD millionths at the gateway's prices, so the estimate is the budget. The pressure threshold is each
// account's `utilizationCeiling`, as the capacity controller's.

/**
 * The API-equivalent of one account's tokens settled in one UTC hour; [unpriced] when a model of that hour has no
 * price.
 *
 * @property hourNumber The hour, since the epoch.
 * @property micros Its cost, USD millionths.
 * @property unpriced Whether a model with tokens that hour has no price.
 */
data class HourCost(val hourNumber: Long, val micros: Long, val unpriced: Boolean = false)

/**
 * The learned full capacity of one shared window of one account, in API-equivalent USD millionths: what the whole
 * window (0 to 100 %) is worth at API prices. Null below [MIN_CAPACITY_SAMPLES] samples; [samples] says how many exist.
 *
 * @property window The window.
 * @property fullWindowMicros The median of the samples, what the automatic budget multiplies.
 * @property samples How many samples exist.
 * @property capacityMicros Their upper quartile ([CAPACITY_QUANTILE]), what the capacity and the outside-use split
 *   report.
 */
data class WindowCapacity(
    val window: String,
    val fullWindowMicros: Long?,
    val samples: Int,
    val capacityMicros: Long? = fullWindowMicros,
)

/** Samples a window needs before its capacity is known. */
const val MIN_CAPACITY_SAMPLES: Int = 3

/** A utilization rise smaller than this is mostly rounding (providers report whole points). */
const val MIN_CAPACITY_DELTA: Double = .05

/** Hourly usage buckets make shorter pairs noisy: a pair also spans at least this long. */
val MIN_CAPACITY_PAIR: Duration = 1.hours

/** The retention of `sharing_quota_history`: a longer look-back needs a retention change first. */
val CAPACITY_HISTORY: Duration = 7.days

/** The quantile of the samples reported as a window's capacity: samples are mostly lower bounds. */
const val CAPACITY_QUANTILE: Double = .75

/** Reported resets may jitter between readings; readings whose resets round to the same minute share a period. */
private const val RESET_GRANULARITY_MILLIS = 60_000L
private const val HOUR_MILLIS = 3_600_000L
private const val RISE_TOLERANCE = 1e-9

/**
 * One sample = the API-equivalent settled between two readings of the same window period (same known reset), at least
 * [MIN_CAPACITY_DELTA] and [MIN_CAPACITY_PAIR] apart, ÷ their utilization rise (resets equal to the minute). Readings
 * are walked in time order: a rise of five points closes a pair and becomes the next anchor, a decrease restarts the
 * anchor. Hours are priced in proportion to the part of the hour the pair covers. A pair that covers an unpriced model,
 * or no traffic at all (the subscription was used outside Ostrom), is no sample.
 *
 * The provider meters every use of the subscription, Ostrom's or not, while the cost only counts Ostrom's: a sample is
 * mostly a lower bound of the window's capacity. Two figures come out of the last seven days' samples: the median
 * ([WindowCapacity.fullWindowMicros]), which the budget uses and which stays conservative against noise that inflates a
 * sample; and the upper quartile ([WindowCapacity.capacityMicros]) for the reported capacity.
 */
class EstimateWindowCapacityUseCase {
    /**
     * The capacity of [window].
     *
     * @param window The window.
     * @param history Its readings.
     * @param hours The account's hours, priced.
     * @param now The time.
     */
    fun estimate(window: String, history: List<QuotaWindow>, hours: List<HourCost>, now: Instant): WindowCapacity {
        val costs = hours.associateBy { it.hourNumber }
        val since = now - CAPACITY_HISTORY
        val samples = history
            .mapNotNull { reading ->
                reading.resetsAt?.takeIf { reading.observedAt in since..now && reading.utilization in 0.0..1.0 }
                    ?.let { periodOf(it) to reading }
            }
            .groupBy({ it.first }, { it.second }).values
            .flatMap { samplesOf(it, costs) }
        if (samples.size < MIN_CAPACITY_SAMPLES) return WindowCapacity(window, null, samples.size, null)
        val sorted = samples.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        // Nearest rank: with three samples this is the largest, with eight the sixth.
        val upper = sorted[(ceil(CAPACITY_QUANTILE * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        return WindowCapacity(window, median.roundToLong(), samples.size, upper.roundToLong())
    }

    private fun periodOf(reset: Instant): Long =
        (reset.toEpochMilliseconds() + RESET_GRANULARITY_MILLIS / 2) / RESET_GRANULARITY_MILLIS

    private fun samplesOf(period: List<QuotaWindow>, costs: Map<Long, HourCost>): List<Double> {
        val samples = mutableListOf<Double>()
        var anchor: QuotaWindow? = null
        for (reading in period.sortedBy { it.observedAt }) {
            val start = anchor
            if (start == null || reading.utilization < start.utilization) {
                anchor = reading
            } else if (closes(start, reading)) {
                val rise = reading.utilization - start.utilization
                val priced = pricedBetween(costs, start.observedAt, reading.observedAt)
                priced?.takeIf { it > 0 }?.let { samples += it / rise }
                anchor = reading
            }
        }
        return samples
    }

    private fun closes(start: QuotaWindow, reading: QuotaWindow): Boolean =
        reading.utilization - start.utilization + RISE_TOLERANCE >= MIN_CAPACITY_DELTA &&
            reading.observedAt - start.observedAt >= MIN_CAPACITY_PAIR
}

/** The API-equivalent of Ostrom's traffic between [from] and [to], hours prorated; null when an hour is unpriced. */
internal fun pricedBetween(costs: Map<Long, HourCost>, from: Instant, to: Instant): Double? {
    val start = from.toEpochMilliseconds()
    val end = to.toEpochMilliseconds()
    val hours = if (end <= start) LongRange.EMPTY else start / HOUR_MILLIS..(end - 1) / HOUR_MILLIS
    val covered = hours.mapNotNull { hour -> costs[hour]?.let { hour to it } }
    if (covered.any { it.second.unpriced }) return null
    return covered.sumOf { (hour, cost) ->
        val overlap = minOf(end, (hour + 1) * HOUR_MILLIS) - maxOf(start, hour * HOUR_MILLIS)
        cost.micros.toDouble() * overlap / HOUR_MILLIS
    }
}

/**
 * What one account's current window has consumed since it opened, split between Ostrom and everything else.
 *
 * @property window The window.
 * @property since Its opening: its reset minus its length.
 * @property observedAt The newest reading.
 * @property resetsAt Its reset.
 * @property observedShare The provider's reading (0.0 to 1.0).
 * @property ostromMicros The API-equivalent Ostrom settled on the account since [since] up to the reading (hours
 *   prorated); null with an unpriced model in the window.
 * @property ostromShare [ostromMicros] ÷ the learned capacity; null without one.
 * @property outsideShare Observed − Ostrom, never negative: the use of the subscription outside Ostrom.
 * @property outsideMicros [outsideShare] in API-equivalent.
 * @property remainingMicros (1 − observed) × capacity: what is left of the window.
 */
data class WindowUsageSplit(
    val window: String,
    val since: Instant,
    val observedAt: Instant,
    val resetsAt: Instant,
    val observedShare: Double,
    val ostromMicros: Long?,
    val ostromShare: Double?,
    val outsideShare: Double?,
    val outsideMicros: Long?,
    val remainingMicros: Long?,
)

/**
 * Splits the newest reading of a window between Ostrom and outside use: the provider's percentage counts every use of
 * the subscription, the ledger only Ostrom's, so the difference is what was used elsewhere. Null without a reading of a
 * current period (known reset in the future) or for a window of unknown length.
 */
class SplitWindowUsageUseCase {
    /**
     * The split of [window].
     *
     * @param window The window.
     * @param history Its readings.
     * @param hours The account's hours, priced.
     * @param fullWindowMicros Its learned capacity, if any.
     * @param now The time.
     */
    fun split(
        window: String,
        history: List<QuotaWindow>,
        hours: List<HourCost>,
        fullWindowMicros: Long?,
        now: Instant,
    ): WindowUsageSplit? {
        val length = windowPeriod(window) ?: return null
        return history
            .mapNotNull { reading -> currentReset(reading, now)?.let { reading to it } }
            .maxByOrNull { it.first.observedAt }
            ?.let { (latest, resetsAt) ->
                val since = resetsAt - length
                val ostrom = pricedBetween(hours.associateBy { it.hourNumber }, since, latest.observedAt)
                val full = fullWindowMicros?.takeIf { it > 0 }?.toDouble()
                val ostromShare = full?.let { capacity -> ostrom?.let { it / capacity } }
                val outside = ostromShare?.let { (latest.utilization - it).coerceAtLeast(0.0) }
                WindowUsageSplit(
                    window, since, latest.observedAt, resetsAt, latest.utilization, ostrom?.roundToLong(), ostromShare,
                    outside, full?.let { capacity -> outside?.let { (it * capacity).roundToLong() } },
                    full?.let { ((1 - latest.utilization) * it).roundToLong() },
                )
            }
    }
}

/** How an account counts in the pool. */
enum class PoolContribution {
    /** Counted: [AccountPoolShare.dailyMicros] is its daily capacity, possibly zero (weekly window at the ceiling). */
    COUNTED,

    /** Paused, pending handover, to reconnect or cooling down: not an account that can serve. */
    UNAVAILABLE,

    /** No fresh five-hour or seven-day observation: nothing says what is left. */
    NO_FRESH_OBSERVATION,

    /** An observed shared window has fewer than [MIN_CAPACITY_SAMPLES] samples, so its capacity is unknown. */
    NO_ESTIMATE,
}

/**
 * One account's part of the pool.
 *
 * @property accountId The account.
 * @property status How it counts.
 * @property dailyMicros What is left of its daily capacity today.
 * @property binding The window that gives the smaller daily figure.
 * @property capacityDailyMicros Its full daily capacity whatever is used now ([dailyCapacity]), null while unknown.
 */
data class AccountPoolShare(
    val accountId: Uuid,
    val status: PoolContribution,
    val dailyMicros: Long,
    val binding: String? = null,
    val capacityDailyMicros: Long? = null,
)

private const val FIVE_HOUR = "five_hour"
private const val SEVEN_DAY = "seven_day"
private const val HOURS_A_DAY = 24.0
private const val FIVE_HOURS = 5
private const val DAYS_A_WEEK = 7.0

/** The provider windows the pool counts: the shared ones, never a `model:` window. */
val SHARED_POOL_WINDOWS: List<String> = listOf(FIVE_HOUR, SEVEN_DAY)

/**
 * An account's full daily capacity in API-equivalent ([WindowCapacity.capacityMicros]), independent of what it has
 * used: the smaller of a day of five-hour windows (C5 × 24 / 5) and a seventh of its weekly window (C7 / 7), over the
 * shared windows it reports ([reported]). Null when it reports none or one of them has no estimate: unknown, never
 * guessed.
 *
 * @param reported The windows the account reports.
 * @param capacities Its learned capacities, by window.
 */
fun dailyCapacity(reported: Collection<String>, capacities: Map<String, WindowCapacity>): Long? {
    val windows = reported.filter { it in SHARED_POOL_WINDOWS }.distinct()
    val terms = windows.mapNotNull { window ->
        capacities[window]?.capacityMicros?.let {
            if (window == FIVE_HOUR) it * HOURS_A_DAY / FIVE_HOURS else it / DAYS_A_WEEK
        }
    }
    return if (windows.isEmpty() || terms.size < windows.size) null else terms.min().roundToLong()
}

/**
 * The pool's daily capacity.
 *
 * @property dailyMicros What is left of the accounts' capacity today, USD millionths; the automatic budget when
 *   [reason] is null.
 * @property accounts Each account's part.
 * @property reason Why there is no automatic value ([NO_ACCOUNTS], [NO_ESTIMATE]); then the typed budget applies.
 * @property capacityDailyMicros The sum of the known full daily capacities of the accounts that can serve.
 * @property capacityUnknownAccounts Accounts that can serve but whose capacity is unknown: [capacityDailyMicros] is a
 *   floor then.
 */
data class PoolSizeEstimate(
    val dailyMicros: Long,
    val accounts: List<AccountPoolShare>,
    val reason: String?,
    val capacityDailyMicros: Long = 0,
    val capacityUnknownAccounts: Int = 0,
) {
    /** The reasons. */
    companion object {
        /** The org shares no account. */
        const val NO_ACCOUNTS: String = "no_accounts"

        /** No account has a figure, or the sum is zero while an account is left out. */
        const val NO_ESTIMATE: String = "no_estimate"
    }
}

/**
 * Sizes the pool from the remaining capacity of its accounts, read from [AccountHeadroomUseCase] (the computation
 * pacing uses): same eligibility, same ceilings, same resets. Only shared windows count.
 *
 * Per account, with C5 and C7 the learned full capacities of its five-hour and seven-day windows and its ceiling:
 * - five-hour term: C5 × ceiling × 24 / 5, the steady rate of a day of five-hour windows;
 * - seven-day term: the remaining points × C7 spread over the days left before the reset (seven when it is unknown);
 *   when the reset comes within the day, plus the share of a fresh window (ceiling × C7 / 7 per day) after it.
 * The account counts the smaller term. A window the provider does not report does not bind; a window observed without
 * an estimate, a learned window without a fresh reading, or no fresh observation at all, leaves the account without a
 * figure. When no account has a figure, or the sum is zero while an account is left out, there is no automatic value.
 *
 * @param headroom The remaining capacity of the accounts.
 */
class SizeSharedPoolUseCase(private val headroom: AccountHeadroomUseCase = AccountHeadroomUseCase()) {
    /**
     * The estimate.
     *
     * @param accounts The pool's accounts and their readings.
     * @param capacities Their learned capacities, by account.
     * @param now The time.
     */
    fun size(
        accounts: List<PoolAccountObservation>,
        capacities: Map<Uuid, List<WindowCapacity>>,
        now: Instant,
    ): PoolSizeEstimate {
        if (accounts.isEmpty()) return PoolSizeEstimate(0, emptyList(), PoolSizeEstimate.NO_ACCOUNTS)
        val serving = (headroom.usable(accounts, now) + headroom.exhausted(accounts, now)).associateBy { it.account.id }
        val shares = accounts.map { observed ->
            val id = observed.account.id
            val learned = capacities[id].orEmpty().associateBy { it.window }
            val capacity = dailyCapacity(observed.windows.map { it.window }, learned)
            val current = serving[id]
            if (current == null) {
                AccountPoolShare(id, PoolContribution.UNAVAILABLE, 0, capacityDailyMicros = capacity)
            } else {
                share(id, current, learned, now).copy(capacityDailyMicros = capacity)
            }
        }
        val canServe = shares.filter { it.status != PoolContribution.UNAVAILABLE }
        val counted = canServe.filter { it.status == PoolContribution.COUNTED }
        val micros = counted.sumOf { it.dailyMicros }
        // A zero sum while an account that can serve is left out would refuse everyone: no automatic value then.
        val leftOut = counted.size < canServe.size
        val reason = if (counted.isEmpty() || (micros == 0L && leftOut)) PoolSizeEstimate.NO_ESTIMATE else null
        return PoolSizeEstimate(
            micros, shares, reason,
            canServe.sumOf { it.capacityDailyMicros ?: 0 }, canServe.count { it.capacityDailyMicros == null },
        )
    }

    private fun share(
        id: Uuid,
        current: AccountHeadroom,
        capacities: Map<String, WindowCapacity>,
        now: Instant,
    ): AccountPoolShare {
        val observed = SHARED_POOL_WINDOWS.mapNotNull { current.window(it) }
        // A window learned before but without a fresh reading (its reset just passed) could bind: no figure until it is
        // read again.
        val stale = SHARED_POOL_WINDOWS.any { current.window(it) == null && capacities[it]?.fullWindowMicros != null }
        val terms = observed.mapNotNull { window ->
            capacities[window.window]?.fullWindowMicros
                ?.let { window.window to term(window, it.toDouble(), current, now) }
        }
        return when {
            observed.isEmpty() || stale -> AccountPoolShare(id, PoolContribution.NO_FRESH_OBSERVATION, 0)
            terms.size < observed.size -> AccountPoolShare(id, PoolContribution.NO_ESTIMATE, 0)
            else -> terms.minBy { it.second }.let { (binding, daily) ->
                AccountPoolShare(id, PoolContribution.COUNTED, daily.roundToLong(), binding)
            }
        }
    }

    private fun term(window: WindowHeadroom, full: Double, current: AccountHeadroom, now: Instant): Double =
        if (window.window == FIVE_HOUR) {
            full * current.ceiling * HOURS_A_DAY / FIVE_HOURS
        } else {
            weekly(window, full, now, current.ceiling)
        }

    private fun weekly(window: WindowHeadroom, full: Double, now: Instant, ceiling: Double): Double {
        val untilReset = window.resetsAt?.let { it - now }
        return when {
            // An unknown reset may be up to a week away: spread what is left over the whole week.
            untilReset == null -> window.remainingShare * full / DAYS_A_WEEK
            untilReset >= 1.days -> window.remainingShare * full / (untilReset / 1.days)
            // Everything left is usable before the reset, then a fresh window spread over its seven days.
            else -> window.remainingShare * full + ceiling * full / DAYS_A_WEEK * ((1.days - untilReset) / 1.days)
        }
    }
}

/**
 * The automatic value the job stored last.
 *
 * @property micros The daily budget, USD millionths.
 * @property computedAt When it was stored.
 */
data class AutomaticDaily(val micros: Long, val computedAt: Instant)

/** When and how the automatic value applies (TeamCode's `AutomaticPoolSize`, Ostrom having one pool per org). */
object AutomaticPoolSize {
    /** An automatic value older than this (the job stopped, or has had no estimate for an hour) gives way. */
    val MAX_AGE: Duration = 1.hours

    /** Every ten minutes, under one owner across replicas. */
    val REFRESH: Duration = 10.minutes

    /** A new value moves at most this share of the previous one per hour, so a noisy estimate cannot swing the pool. */
    const val MAX_STEP_PER_HOUR: Double = .2

    /** A previous value older than this is no reference: the estimate applies as is. */
    val SMOOTHING_MEMORY: Duration = 2.hours

    /**
     * The org's daily budget: the automatic value when the setting is on and the value is fresh, the typed one
     * otherwise (null when it has none).
     */
    fun effectiveBudget(manual: Long?, automatic: AutomaticDaily?, enabled: Boolean, now: Instant): Long? =
        automatic?.takeIf { usesAutomatic(it, enabled, now) }?.micros ?: manual

    /** Whether the automatic value applies. */
    fun usesAutomatic(automatic: AutomaticDaily?, enabled: Boolean, now: Instant): Boolean =
        enabled && automatic != null && isFresh(automatic, now)

    /** Whether the stored value is young enough to apply. */
    fun isFresh(automatic: AutomaticDaily, now: Instant): Boolean = now - automatic.computedAt in Duration.ZERO..MAX_AGE

    /**
     * At most ±[MAX_STEP_PER_HOUR] of [previous] per elapsed hour; nothing scales from zero, so a pool emptied by
     * resets grows back.
     */
    fun smooth(previous: AutomaticDaily?, target: Long, now: Instant): Long {
        val reference = previous?.takeIf { it.micros > 0 && now - it.computedAt in Duration.ZERO..SMOOTHING_MEMORY }
            ?: return target
        val step = reference.micros * MAX_STEP_PER_HOUR * (minOf(now - reference.computedAt, 1.hours) / 1.hours)
        return target.coerceIn((reference.micros - step).roundToLong(), (reference.micros + step).roundToLong())
    }
}

/**
 * What `GET /api/v1/pools/size` shows: the setting, both values and the estimate behind the automatic one.
 *
 * @property enabled `autoPoolSize`.
 * @property manualDailyMicros The org's typed daily budget, null without one.
 * @property automatic The value the job stored last, null before its first estimate.
 * @property effectiveDailyMicros The daily budget admission applies now, null when there is none.
 * @property usesAutomatic Whether that is the automatic value.
 * @property estimate The estimate, computed now.
 * @property unpricedModels Models used over seven days without a price, `provider/model`.
 * @property capacities The learned capacities, by account.
 * @property usage Per account, its current shared windows split between Ostrom and outside use.
 * @property mix The org's seven-day model mix.
 */
data class PoolSizeReport(
    val enabled: Boolean,
    val manualDailyMicros: Long?,
    val automatic: AutomaticDaily?,
    val effectiveDailyMicros: Long?,
    val usesAutomatic: Boolean,
    val estimate: PoolSizeEstimate,
    val unpricedModels: List<String>,
    val capacities: Map<Uuid, List<WindowCapacity>>,
    val usage: Map<Uuid, List<WindowUsageSplit>> = emptyMap(),
    val mix: TokenMix = TokenMix(emptyList(), null),
) {
    /** [PoolSizeEstimate.capacityDailyMicros] in tokens at the org's mix; null without priced traffic. */
    val capacityDailyTokens: Long? get() = mix.tokensPerMicro?.let { (estimate.capacityDailyMicros * it).roundToLong() }

    /**
     * What the accounts' current weekly windows used outside Ostrom, in API-equivalent, over the accounts that know it;
     * null when none does. [outsideUnknownAccounts] counts the accounts left out, so with a non-zero count it is a
     * floor.
     */
    val outsideWeeklyMicros: Long? get() = weeklyOutside().filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    /** The accounts whose weekly outside use is unknown. */
    val outsideUnknownAccounts: Int get() = weeklyOutside().count { it == null }

    private fun weeklyOutside() = estimate.accounts.map { share ->
        usage[share.accountId].orEmpty().firstOrNull { it.window == SEVEN_DAY }?.outsideMicros
    }
}

/** The pool's sizing (TeamCode's `PoolSizingService`). */
interface PoolSizingService {
    /**
     * The org's sizing as of now, computed on demand from the same data as the scheduled job: owners and admins.
     *
     * @throws run.ostrom.server.secrets.domain.AdminRequiredException For a member who does not administer the org.
     */
    suspend fun report(caller: Caller): PoolSizeReport

    /** The scheduled job: recompute and store every org's automatic value. */
    suspend fun refresh()
}

/** The reset of a reading of a window still open at [now] (read by then, in range, its reset ahead); null otherwise. */
private fun currentReset(reading: QuotaWindow, now: Instant): Instant? {
    val reset = reading.resetsAt ?: return null
    val open = reset > now && reading.observedAt <= now && reading.utilization in 0.0..1.0
    return if (open) reset else null
}
