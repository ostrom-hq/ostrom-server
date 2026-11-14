// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import run.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [ForecastService] over the accounts, their current windows and their history (TeamCode's `ForecastServiceImpl`):
 * bounded reads, the pressure threshold of each account its own `utilizationCeiling`.
 *
 * @param accounts The accounts.
 * @param windows Their current windows.
 * @param history Their readings over time.
 * @param clock The time.
 */
class ForecastServiceImpl(
    private val accounts: SharedAccountRepository,
    private val windows: QuotaWindowRepository,
    private val history: QuotaHistoryRepository,
    private val clock: Clock,
) : ForecastService {
    override suspend fun load(caller: Caller, accountId: Uuid?): ForecastSnapshot {
        caller.requireAdmin()
        val now = clock.now()
        val listed = accounts.list(caller.orgId).sortedBy { it.id.toString() }
        val shown = listed.take(ForecastService.MAX_ACCOUNTS).map(ForecastAccount::of)
        val selected = if (accountId == null) {
            listed.firstOrNull()
        } else {
            listed.firstOrNull { it.id == accountId } ?: throw AccountNotFoundException()
        } ?: return ForecastSnapshot(now, shown, null, emptyList(), false)
        val threshold = selected.limits.utilizationCeiling
        val all = windows.byAccount(caller.orgId)[selected.id].orEmpty().sortedBy { it.window }
        val current = all.take(ForecastService.MAX_WINDOWS)
        val rows = if (current.isEmpty()) {
            emptyList()
        } else {
            history.recent(
                caller.orgId,
                selected.id,
                current.map { it.window },
                (now - FORECAST_HISTORY)..now,
                ForecastService.MAX_SAMPLES + 1,
            )
        }
        val byWindow = rows.take(ForecastService.MAX_SAMPLES).groupBy { it.window }
        val build = BuildForecastSeriesUseCase(threshold)
        val account = ForecastAccount.of(selected)
        return ForecastSnapshot(
            generatedAt = now,
            accounts = if (account in shown) shown else shown + account,
            account = account,
            windows = current.map { build(it, byWindow[it.window].orEmpty(), now) },
            truncated = listed.size > ForecastService.MAX_ACCOUNTS || all.size > ForecastService.MAX_WINDOWS ||
                rows.size > ForecastService.MAX_SAMPLES,
            pressureThreshold = threshold,
        )
    }
}
