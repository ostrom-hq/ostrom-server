// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * A calendar month in UTC: budgets reset at its end.
 *
 * @property start The first instant of the month.
 * @property end The first instant of the next month.
 */
data class BudgetPeriod(val start: Instant, val end: Instant) {
    /** Finding the month of an instant. */
    companion object {
        /**
         * The month that contains an instant.
         *
         * @param at The instant.
         *
         * @return Its month.
         */
        fun of(at: Instant): BudgetPeriod {
            val date = at.toLocalDateTime(TimeZone.UTC).date
            val first = LocalDate(date.year, date.month, 1)
            return BudgetPeriod(
                start = first.atStartOfDayIn(TimeZone.UTC),
                end = first.plus(1, DateTimeUnit.MONTH).atStartOfDayIn(TimeZone.UTC),
            )
        }
    }
}
