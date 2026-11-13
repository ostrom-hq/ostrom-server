// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * A calendar month or a day in UTC: budgets reset at its end.
 *
 * @property start The first instant of the period.
 * @property end The first instant of the next one.
 */
data class BudgetPeriod(val start: Instant, val end: Instant) {
    /** Finding the month or the day of an instant. */
    companion object {
        /**
         * The UTC day that contains an instant.
         *
         * @param at The instant.
         *
         * @return Its day.
         */
        fun day(at: Instant): BudgetPeriod {
            val date = at.toLocalDateTime(TimeZone.UTC).date
            return BudgetPeriod(
                start = date.atStartOfDayIn(TimeZone.UTC),
                end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC),
            )
        }

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

/** How long a limit counts before it starts again: a UTC day or a UTC calendar month. */
enum class BudgetWindow {
    /** From 00:00 UTC to the next. */
    DAY,

    /** From the 1st of the month, 00:00 UTC, to the next 1st. */
    MONTH,
    ;

    /**
     * The period of this window that contains an instant.
     *
     * @param at The instant.
     *
     * @return Its day or its month.
     */
    fun periodOf(at: Instant): BudgetPeriod = when (this) {
        DAY -> BudgetPeriod.day(at)
        MONTH -> BudgetPeriod.of(at)
    }
}

/** What a reached limit does. */
enum class BudgetAction {
    /** The gateway refuses model calls until the period ends. */
    BLOCK,

    /** Calls go on; the overrun is written to the audit log and listed by `GET /spending`. */
    WARN,
}

/**
 * One limit: an amount per day or per month, that stops the calls or only warns.
 *
 * @property micros The amount, USD millionths.
 * @property window Per day or per month.
 * @property action Stops or warns.
 */
data class BudgetLimit(
    val micros: Long,
    val window: BudgetWindow = BudgetWindow.MONTH,
    val action: BudgetAction = BudgetAction.BLOCK,
)

/**
 * The monthly amount of a subject's limits, whatever it does: what a member is shown as their monthly budget.
 *
 * @return The amount, or null without a monthly limit.
 */
fun List<BudgetLimit>.monthlyMicros(): Long? = firstOrNull { it.window == BudgetWindow.MONTH }?.micros
