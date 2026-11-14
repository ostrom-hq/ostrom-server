// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import run.ostrom.server.secrets.domain.Caller
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A shared account as the Forecasts page lists it: public administration metadata only, never its tokens nor its
 * provider account id.
 *
 * @property id The account.
 * @property provider The subscription.
 * @property label The name admins see.
 * @property status Whether it serves.
 * @property ownership Who renews its tokens (shown in place of `ready` while not Ostrom).
 * @property email The address its owner gave, if any.
 */
data class ForecastAccount(
    val id: Uuid,
    val provider: AccountProvider,
    val label: String,
    val status: AccountStatus,
    val ownership: RefreshOwnership,
    val email: String?,
) {
    /** Building one from an account. */
    companion object {
        /** The metadata of [account]. */
        fun of(account: SharedAccount): ForecastAccount = ForecastAccount(
            account.id,
            account.provider,
            account.label,
            account.status,
            account.ownership,
            account.email,
        )
    }
}

/**
 * A current window, its history cut into segments, and its forecast.
 *
 * @property quota The latest reading.
 * @property forecast What it says.
 * @property segments The last hour's readings, oldest first ([ForecastHistoryUseCase.segments]).
 */
data class ForecastSeries(val quota: QuotaWindow, val forecast: QuotaForecast, val segments: List<List<QuotaWindow>>) {
    /** Readings in the latest segment (the rate needs three). */
    val learningSamples: Int get() = segments.lastOrNull()?.size ?: 0

    /** Time between the first and last readings of the latest segment. */
    val learningSpan: Duration
        get() = segments.lastOrNull()?.let { it.last().observedAt - it.first().observedAt } ?: Duration.ZERO
}

/**
 * A bounded point-in-time view of one account's windows, never a sum of percentages of different plans.
 *
 * @property generatedAt When it was read.
 * @property accounts The org's accounts by id, at most [ForecastService.MAX_ACCOUNTS] (and the selected one).
 * @property account The selected account, or null when the org has none.
 * @property windows Its current windows by name, at most [ForecastService.MAX_WINDOWS].
 * @property truncated Whether a bound left something out.
 * @property pressureThreshold The account's `utilizationCeiling`.
 */
data class ForecastSnapshot(
    val generatedAt: Instant,
    val accounts: List<ForecastAccount>,
    val account: ForecastAccount?,
    val windows: List<ForecastSeries>,
    val truncated: Boolean,
    val pressureThreshold: Double = AccountLimits.DEFAULT_CEILING,
)

/** TeamCode's Forecasts page: the forecasts of one shared account, for the org's owners and admins. */
interface ForecastService {
    /**
     * The snapshot.
     *
     * @param caller Who asks: an owner or an admin.
     * @param accountId The account, or null for the org's first by id.
     *
     * @return The snapshot.
     *
     * @throws run.ostrom.server.secrets.domain.AdminRequiredException When [caller] does not administer the org.
     * @throws AccountNotFoundException When [accountId] is not one of the org's accounts.
     */
    suspend fun load(caller: Caller, accountId: Uuid? = null): ForecastSnapshot

    /** The bounds of a snapshot. */
    companion object {
        /** Accounts listed at most. */
        const val MAX_ACCOUNTS: Int = 64

        /** Windows of the account at most. */
        const val MAX_WINDOWS: Int = 128

        /** Readings read at most. */
        const val MAX_SAMPLES: Int = 8_192
    }
}

/**
 * Joins a current window with its retained history without inventing missing readings (TeamCode's
 * `BuildForecastSeriesUseCase`).
 *
 * @param threshold The account's `utilizationCeiling`.
 */
class BuildForecastSeriesUseCase(private val threshold: Double = AccountLimits.DEFAULT_CEILING) {
    /**
     * The series.
     *
     * @param current The window's latest reading.
     * @param history Its earlier readings.
     * @param now The time.
     *
     * @return The series: a stale reading stays stale whatever the history.
     */
    operator fun invoke(current: QuotaWindow, history: List<QuotaWindow>, now: Instant): ForecastSeries {
        val samples = history.filter { it.window == current.window && it.observedAt < current.observedAt }
            .sortedBy { it.observedAt } + current
        val useCase = ForecastHistoryUseCase(threshold)
        val baseline = ForecastQuotaUseCase().forecast(
            QuotaObservation(current.utilization, current.observedAt, current.resetsAt),
            null,
            now,
            threshold,
        )
        val forecast = if (baseline.state == QuotaForecast.State.STALE) {
            baseline
        } else {
            // Not stale: the current reading is the latest, and not after now.
            useCase.evaluate(samples, current, now).forecast
        }
        return ForecastSeries(current, forecast, useCase.segments(samples, now))
    }
}
