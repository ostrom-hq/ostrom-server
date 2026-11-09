// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage.domain

import run.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Spending over a period (contract `Usage`): the totals, per member and per model.
 *
 * @property from The start, included.
 * @property to The end, excluded.
 * @property total Every event of the period.
 * @property byMember Per member (key null: events of no member).
 * @property byModel Per model, model calls only.
 */
data class UsageReport(
    val from: Instant,
    val to: Instant,
    val total: UsageTotal,
    val byMember: List<UsageTotal>,
    val byModel: List<UsageTotal>,
)

/** Reads the spending of a period: an admin the org's or one member's, a member only their own. */
interface UsageReportUseCase {
    /**
     * Reads it.
     *
     * @param caller The caller.
     * @param from The start, or null for 30 days before [to].
     * @param to The end, or null for now.
     * @param memberId For an admin, one member only; ignored for a member, who always reads their own.
     *
     * @return The report.
     *
     * @throws InvalidUsageQueryException When the period is empty or longer than [MAX_PERIOD_DAYS] days.
     */
    suspend operator fun invoke(caller: Caller, from: Instant?, to: Instant?, memberId: Uuid?): UsageReport

    /** The bounds of a report. */
    companion object {
        /** The default period. */
        const val DEFAULT_PERIOD_DAYS: Int = 30

        /** The longest period: the retention, so that a report never scans more than the ledger keeps. */
        const val MAX_PERIOD_DAYS: Int = 400
    }
}

/**
 * [UsageReportUseCase] on the ledger: three bounded aggregates on the `(org_id, at)` and `(org_id, member_id, at)`
 * indexes.
 *
 * @param repository The ledger.
 * @param clock The time.
 */
class UsageReportUseCaseImpl(private val repository: UsageRepository, private val clock: Clock) : UsageReportUseCase {
    override suspend fun invoke(caller: Caller, from: Instant?, to: Instant?, memberId: Uuid?): UsageReport {
        val end = to ?: clock.now()
        val start = from ?: (end - UsageReportUseCase.DEFAULT_PERIOD_DAYS.days)
        if (start >= end) throw InvalidUsageQueryException("from is before to.")
        if (end - start > UsageReportUseCase.MAX_PERIOD_DAYS.days) {
            throw InvalidUsageQueryException("A period is at most ${UsageReportUseCase.MAX_PERIOD_DAYS} days.")
        }
        val member = if (caller.role.isAdmin) memberId else caller.memberId
        // Without an end, no upper bound: an event recorded at this very instant still counts.
        val window = UsageWindow(caller.orgId, member, start, to)
        val byKind = repository.summary(window, UsageGroupBy.KIND, UsageRules.MAX_GROUPS)
        return UsageReport(
            from = start,
            to = end,
            // Grouped by kind first: five kinds at most, so the total is never cut by the group limit.
            total = UsageSummary(start, UsageGroupBy.KIND, byKind).total,
            byMember = repository.summary(window, UsageGroupBy.MEMBER, UsageRules.MAX_GROUPS),
            byModel = repository.summary(window, UsageGroupBy.MODEL, UsageRules.MAX_GROUPS),
        )
    }
}
