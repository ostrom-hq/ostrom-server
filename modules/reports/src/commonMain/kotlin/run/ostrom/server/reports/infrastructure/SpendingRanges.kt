// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.infrastructure

import run.ostrom.server.reports.domain.dayOf
import run.ostrom.server.reports.domain.startOf
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A window of the ledger cut in three for the spending report (ReportsQueries.spending): the whole UTC days, read from
 * the daily totals, and the hours before the first of them and after the last, read from the calls. The three never
 * overlap and cover the window exactly; a window without a whole day is all hours.
 *
 * @property days The whole days, from the first included to the last excluded.
 * @property head The hours before the first whole day, from included to excluded.
 * @property tail The hours after the last whole day, from included to excluded.
 */
internal data class SpendingRanges(
    val days: Pair<LocalDate, LocalDate>,
    val head: Pair<Instant, Instant>,
    val tail: Pair<Instant, Instant>,
) {
    /**
     * Binds the window's parts to the statement of ReportsQueries.spending.
     *
     * @param statement The statement.
     * @return It.
     */
    fun bindTo(statement: Statement): Statement = statement
        .bind("day_from", days.first.toString()).bind("day_to", days.second.toString())
        .bind("head_from", head.first).bind("head_to", head.second)
        .bind("tail_from", tail.first).bind("tail_to", tail.second)

    /** Cutting a window. */
    companion object {
        /**
         * Cuts the window from [from] (included) to [to] (excluded).
         *
         * @param from The start.
         * @param to The end.
         * @return Its parts.
         */
        fun of(from: Instant, to: Instant): SpendingRanges {
            val first = dayOf(from).let { if (startOf(it) == from) it else it.plus(1, DateTimeUnit.DAY) }
            val end = dayOf(to)
            if (first >= end) return allHours(from, to)
            return SpendingRanges(first to end, from to startOf(first), startOf(end) to to)
        }

        /**
         * The window from [from] to [to] with no whole day: every hour of it added up from the calls (no day, and
         * an empty tail), as a window inside one day is read.
         *
         * @param from The start.
         * @param to The end.
         * @return Its parts.
         */
        fun allHours(from: Instant, to: Instant): SpendingRanges = dayOf(to).let { end ->
            SpendingRanges(end to end, from to to, to to to)
        }
    }
}

/**
 * The statement of the spending report, its window cut in three ([SpendingRanges]).
 *
 * @param orgId The organization.
 * @param memberId One member's spending only, when set.
 * @param from The start, included.
 * @param to The end, excluded.
 * @return The statement.
 */
internal fun spendingStatement(orgId: Uuid, memberId: Uuid?, from: Instant, to: Instant): Statement {
    val statement = Statement.create(ReportsQueries.spending(member = memberId != null)).bind("org_id", orgId)
    memberId?.let { statement.bind("member_id", it) }
    return SpendingRanges.of(from, to).bindTo(statement)
}
