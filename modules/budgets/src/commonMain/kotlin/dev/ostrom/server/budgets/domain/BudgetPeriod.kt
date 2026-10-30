// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import kotlin.time.Instant

/**
 * A calendar month in UTC: budgets reset at its end.
 *
 * @property start The first instant of the month.
 * @property end The first instant of the next month.
 */
data class BudgetPeriod(val start: Instant, val end: Instant) {
    companion object {
        private const val SECONDS_PER_DAY = 86_400L

        /**
         * The month that contains an instant.
         *
         * @param at The instant.
         *
         * @return Its month.
         */
        fun of(at: Instant): BudgetPeriod {
            val (year, month) = yearMonth(at.epochSeconds.floorDiv(SECONDS_PER_DAY))
            val start = daysFromCivil(year, month, 1)
            val end = daysFromCivil(year + month / 12, month % 12 + 1, 1)
            return BudgetPeriod(Instant.fromEpochSeconds(start * SECONDS_PER_DAY), Instant.fromEpochSeconds(end * SECONDS_PER_DAY))
        }

        // Howard Hinnant's civil calendar algorithms (proleptic Gregorian), on days since 1970-01-01.
        private fun yearMonth(days: Long): Pair<Long, Int> {
            val shifted = days + 719_468
            val era = shifted.floorDiv(146_097L)
            val dayOfEra = shifted - era * 146_097
            val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val shiftedMonth = (5 * dayOfYear + 2) / 153
            val month = (if (shiftedMonth < 10) shiftedMonth + 3 else shiftedMonth - 9).toInt()
            val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
            return year to month
        }

        private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
            val adjustedYear = if (month <= 2) year - 1 else year
            val era = adjustedYear.floorDiv(400L)
            val yearOfEra = adjustedYear - era * 400
            val dayOfYear = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era * 146_097 + dayOfEra - 719_468
        }
    }
}
