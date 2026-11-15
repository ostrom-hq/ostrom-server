// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.gateway.domain.OrgPrices
import run.ostrom.server.gateway.domain.PacingSettingsRepository
import run.ostrom.server.gateway.domain.PriceTable
import run.ostrom.server.gateway.domain.TokenUsage
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.sharing.domain.PoolAccountObservation
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import run.ostrom.server.sharing.domain.QuotaWindowRepository
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.domain.SharingOrgs
import run.ostrom.server.usage.domain.TokenBucket
import run.ostrom.server.usage.domain.TokenUsageReader
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the estimate reads: the org's shared accounts and their current windows, seven days of their readings
 * (`sharing_quota_history`) and of their hourly tokens, seven days of the org's tokens for the model mix, and the org's
 * prices.
 *
 * @property accounts The shared accounts.
 * @property windows Their current windows.
 * @property history Their readings.
 * @property tokens The ledger's tokens.
 * @property prices The org's price table.
 */
class PoolSizingSources(
    val accounts: SharedAccountRepository,
    val windows: QuotaWindowRepository,
    val history: QuotaHistoryRepository,
    val tokens: TokenUsageReader,
    val prices: OrgPrices,
)

/**
 * [PoolSizingService] (TeamCode's `SqlPoolSizing`): [refresh] estimates every org that shares accounts and stores the
 * smoothed value; [AutomaticPoolBudgetsRepository] applies it while the setting is on and the value is fresh; [report]
 * recomputes the same estimate on demand. Every read is bounded (64 accounts, 12,000 readings a window, 5,000 hours
 * an account, 5,000 rows of the mix) and goes through an index prefix.
 *
 * @param sources What the estimate reads.
 * @param settings The org's capacity controller settings (`autoPoolSize`).
 * @param limits The typed limits (the org's daily one is the manual budget).
 * @param store The stored automatic value.
 * @param orgs The orgs that share accounts.
 * @param clock The time.
 */
class PoolSizingServiceImpl(
    private val sources: PoolSizingSources,
    private val settings: PacingSettingsRepository,
    private val limits: BudgetsRepository,
    private val store: PoolSizeRepository,
    private val orgs: SharingOrgs,
    private val clock: Clock,
) : PoolSizingService {
    private val estimator = EstimateWindowCapacityUseCase()
    private val splitter = SplitWindowUsageUseCase()
    private val sizer = SizeSharedPoolUseCase()

    override suspend fun report(caller: Caller): PoolSizeReport {
        caller.requireAdmin()
        val orgId = caller.orgId
        val now = clock.now()
        val inputs = inputs(orgId, now)
        val enabled = settings.get(orgId).autoPoolSize
        val stored = store.read(orgId)
        val manual = limits.settings(orgId, 0).org.firstOrNull { it.window == BudgetWindow.DAY }?.micros
        return PoolSizeReport(
            enabled = enabled,
            manualDailyMicros = manual,
            automatic = stored,
            effectiveDailyMicros = AutomaticPoolSize.effectiveBudget(manual, stored, enabled, now),
            usesAutomatic = AutomaticPoolSize.usesAutomatic(stored, enabled, now),
            estimate = inputs.estimate,
            unpricedModels = inputs.mix.unpricedModels,
            capacities = inputs.capacities,
            usage = inputs.usage,
            mix = inputs.mix,
        )
    }

    // An org whose pass fails leaves the others to run; the first failure is thrown at the end, for the job's log.
    override suspend fun refresh() {
        var failure: Exception? = null
        for (orgId in orgs.withAccounts()) {
            try {
                refresh(orgId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = failure ?: error
            }
        }
        failure?.let { throw it }
    }

    /** Without a figure the stored value is left to age: after an hour the typed budget applies again. */
    private suspend fun refresh(orgId: Uuid) {
        // One instant for the estimate, its smoothing and its time: what is stored is what was read.
        val now = clock.now()
        val estimate = inputs(orgId, now).estimate
        if (estimate.reason != null) return
        store.store(orgId, now) { previous ->
            AutomaticPoolSize.smooth(previous, estimate.dailyMicros, now)
                .coerceIn(0, BudgetRules.MAX_LIMIT_MICROS)
        }
    }

    private class Inputs(
        val estimate: PoolSizeEstimate,
        val capacities: Map<Uuid, List<WindowCapacity>>,
        val usage: Map<Uuid, List<WindowUsageSplit>>,
        val mix: TokenMix,
    )

    private suspend fun inputs(orgId: Uuid, now: Instant): Inputs {
        val prices = sources.prices.of(orgId)
        val accounts = sources.accounts.list(orgId)
            .sortedWith(compareByDescending<SharedAccount> { it.priority }.thenBy { it.id.toString() })
            .take(POOL_SIZING_ACCOUNTS)
        val windows = sources.windows.byAccount(orgId)
        val observations = accounts.map { PoolAccountObservation(it, windows[it.id].orEmpty()) }
        val since = now - CAPACITY_HISTORY
        val learned = accounts.associate { account ->
            val costs = hourCosts(prices, orgId, account.id, since)
            account.id to SHARED_POOL_WINDOWS.map { window ->
                // Past the bound, the newest readings.
                val history = sources.history.recent(orgId, account.id, listOf(window), since..now, HISTORY_ROWS)
                    .asReversed()
                val capacity = estimator.estimate(window, history, costs, now)
                capacity to splitter.split(window, history, costs, capacity.capacityMicros, now)
            }
        }
        val capacities = learned.mapValues { (_, windows) -> windows.map { it.first } }
        val splits = learned.mapValues { (_, windows) -> windows.mapNotNull { it.second } }
        val today = BudgetPeriod.day(now).start
        val usage = sources.tokens.orgDays(orgId, today - MIX_DAYS.days, MIX_ROWS)
            .map { PricedTokens(it.provider, it.model, it.bucket, it.tokens()) }
        return Inputs(sizer.size(observations, capacities, now), capacities, splits, TokenMix.of(prices, usage))
    }

    /** One account's hours since [since], priced at the org's prices; an hour with an unpriced model is marked. */
    private suspend fun hourCosts(prices: PriceTable, orgId: Uuid, accountId: Uuid, since: Instant): List<HourCost> =
        priceHours(prices, sources.tokens.accountHours(orgId, accountId, since, HOUR_ROWS))


    private companion object {
        const val POOL_SIZING_ACCOUNTS = 64

        /** One reading a minute for seven days, with margin. */
        const val HISTORY_ROWS = 12_000

        /** Seven days of hours, a few models each. */
        const val HOUR_ROWS = 5_000
        const val MIX_ROWS = 5_000

        /** Today and the six days before it. */
        const val MIX_DAYS = 6
    }
}

/**
 * The ledger's hours of one account at [prices] (TeamCode's `hourCosts`): each hour's cost, and whether a model with
 * tokens had no price in it.
 */
internal fun priceHours(prices: PriceTable, buckets: List<TokenBucket>): List<HourCost> =
    buckets.groupBy { it.bucket }.map { (hour, models) ->
        var micros = 0L
        var unpriced = false
        for (row in models) {
            val cost = prices.costOf(providerOf(row.provider), row.model, row.tokens())
            if (cost != null) {
                micros += cost.totalMicros
            } else if (row.tokens().allTokens > 0) {
                unpriced = true
            }
        }
        HourCost(hour, micros, unpriced)
    }

/** The bucket's tokens, as the gateway prices them. */
internal fun TokenBucket.tokens(): TokenUsage = TokenUsage(input, output, cacheRead, cacheWrite)
