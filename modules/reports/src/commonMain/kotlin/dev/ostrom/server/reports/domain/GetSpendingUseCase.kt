// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.domain

import kotlin.time.Clock

/** Reads the spending report. */
interface GetSpendingUseCase {
    /**
     * Reads it.
     *
     * @param viewer Who reads: the whole organization, or only their own spending.
     * @param period The period.
     * @return The report.
     */
    suspend operator fun invoke(viewer: Viewer, period: SpendingPeriod): SpendingReport
}

/**
 * [GetSpendingUseCase] on the ledger and the budgets. The figures are the server's (the model gateway's measured
 * cost), never what a client declares, the same ones the budgets stop on.
 *
 * @param repository The reads.
 * @param budgets The monthly budgets.
 * @param clock The time.
 */
class GetSpendingUseCaseImpl(
    private val repository: ReportsRepository,
    private val budgets: SpendingBudgets,
    private val clock: Clock,
) : GetSpendingUseCase {
    override suspend fun invoke(viewer: Viewer, period: SpendingPeriod): SpendingReport {
        val now = clock.now()
        val today = dayOf(now)
        val first = period.firstDay(today)
        val from = startOf(first)
        val member = viewer.memberId.takeUnless { viewer.wholeOrganization }
        val totals = repository.spending(viewer.orgId, member, from, now)
        val spent = totals.byDay.associate { it.date to it.micros }
        return SpendingReport(
            period = period,
            from = from,
            to = now,
            totalMicros = totals.byDay.sumOf { it.micros },
            budgetMicros = if (period == SpendingPeriod.MONTH) budgets.monthlyMicros(viewer) else null,
            totals = SpendingTotals(
                byAgent = totals.byAgent.sortedWith(LARGEST_FIRST),
                byRepo = totals.byRepo.sortedWith(LARGEST_FIRST),
                byPerson = totals.byPerson.sortedWith(LARGEST_FIRST),
                byDay = totals.byDay,
            ),
            daily = daysBetween(first, today).map { SpendingDay(it, spent[it] ?: 0) },
        )
    }

}

// Top-level, not in a private companion: a companion property has a getter nothing calls, which Kover counts.
private val LARGEST_FIRST: Comparator<SpendingLine> = compareByDescending<SpendingLine> { it.micros }.thenBy { it.key }
