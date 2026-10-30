// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.domain

import kotlin.time.Instant

/** UTC calendar arithmetic on epoch days, without a time-zone database (H. Hinnant's civil-from-days algorithm). */
internal object CivilCalendar {
    private const val SECONDS_PER_DAY = 86_400L
    private const val DAYS_FROM_0000_03_01 = 719_468L
    private const val DAYS_PER_ERA = 146_097L
    private const val DAYS_PER_4_YEARS = 1_460L
    private const val DAYS_PER_CENTURY = 36_524L
    private const val LAST_DAY_OF_ERA = 146_096L
    private const val DAYS_PER_YEAR = 365L
    private const val YEARS_PER_LEAP = 4L
    private const val YEARS_PER_CENTURY = 100L
    private const val MONTH_SPAN_NUMERATOR = 153L
    private const val MONTH_SPAN_DENOMINATOR = 5L

    /** The first instant of the UTC calendar month of [now]. */
    fun monthStart(now: Instant): Instant {
        val days = now.epochSeconds.floorDiv(SECONDS_PER_DAY)
        val shifted = days + DAYS_FROM_0000_03_01
        val era = shifted.floorDiv(DAYS_PER_ERA)
        val dayOfEra = shifted - era * DAYS_PER_ERA
        val yearOfEra =
            (dayOfEra - dayOfEra / DAYS_PER_4_YEARS + dayOfEra / DAYS_PER_CENTURY - dayOfEra / LAST_DAY_OF_ERA) /
                DAYS_PER_YEAR
        val dayOfYear =
            dayOfEra - (DAYS_PER_YEAR * yearOfEra + yearOfEra / YEARS_PER_LEAP - yearOfEra / YEARS_PER_CENTURY)
        val monthFromMarch = (MONTH_SPAN_DENOMINATOR * dayOfYear + 2) / MONTH_SPAN_NUMERATOR
        val dayOfMonth = dayOfYear - (MONTH_SPAN_NUMERATOR * monthFromMarch + 2) / MONTH_SPAN_DENOMINATOR + 1
        return Instant.fromEpochSeconds((days - dayOfMonth + 1) * SECONDS_PER_DAY)
    }
}
