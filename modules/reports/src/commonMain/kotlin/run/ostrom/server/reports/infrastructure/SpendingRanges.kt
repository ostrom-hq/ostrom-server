// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.infrastructure

import run.ostrom.server.reports.domain.dayOf
import run.ostrom.server.reports.domain.startOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.time.Instant

/**
 * A window of the ledger cut in three for the spending report ([SpendingReads]): the whole UTC days, read from
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
