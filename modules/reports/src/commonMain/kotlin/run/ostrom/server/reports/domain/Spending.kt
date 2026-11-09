// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.reports.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The periods of the spending report, in UTC days like the budgets.
 *
 * @property wire The value of `?period=`.
 */
enum class SpendingPeriod(val wire: String) {
    /** The last seven days, today included. */
    WEEK("week"),

    /** The calendar month so far: the budgets' month. */
    MONTH("month"),
    ;

    /**
     * The first day of the period that contains a day.
     *
     * @param today The day.
     * @return The first day.
     */
    fun firstDay(today: LocalDate): LocalDate = when (this) {
        WEEK -> today.minus(WEEK_DAYS - 1, DateTimeUnit.DAY)
        MONTH -> LocalDate(today.year, today.month, 1)
    }

    /** Reading a period. */
    companion object {
        private const val WEEK_DAYS = 7

        /**
         * Reads `?period=`, the month when absent.
         *
         * @param wire The value.
         * @return The period.
         * @throws InvalidPeriodException When it is neither `week` nor `month`.
         */
        fun of(wire: String?): SpendingPeriod =
            if (wire == null) MONTH else entries.firstOrNull { it.wire == wire } ?: throw InvalidPeriodException()
    }
}

/** A period the report does not know: 400. */
class InvalidPeriodException : OstromException(ErrorKind.INVALID_REQUEST, "The period is week or month.")

/**
 * The spending of one agent, repository or person.
 *
 * @property key The agent, the repository, the member id; empty for spending outside a session (agent, repository)
 *   or of the organization itself (person: an automation).
 * @property label What the console shows: the key, the member's login.
 * @property micros The cost, in millionths of a US dollar.
 * @property sessions The sessions that spent it.
 */
data class SpendingLine(val key: String, val label: String, val micros: Long, val sessions: Long)

/**
 * The spending of one day.
 *
 * @property date The UTC day.
 * @property micros The cost, in millionths of a US dollar.
 */
data class SpendingDay(val date: LocalDate, val micros: Long)

/**
 * What the ledger holds for a window, grouped.
 *
 * @property byAgent By the agent of the session.
 * @property byRepo By the repository of the session.
 * @property byPerson By member.
 * @property byDay By UTC day, only the days that spent.
 */
data class SpendingTotals(
    val byAgent: List<SpendingLine>,
    val byRepo: List<SpendingLine>,
    val byPerson: List<SpendingLine>,
    val byDay: List<SpendingDay>,
)

/**
 * The spending report.
 *
 * @property period The period.
 * @property from The start, included.
 * @property to The end, excluded: now.
 * @property totalMicros What was spent.
 * @property budgetMicros The monthly budget that applies to the viewer, on the month only.
 * @property totals The lines, each sorted by cost, largest first.
 * @property daily Every day of the period up to today, zero included.
 * @property exceeded The budgets the viewer sees that are spent past their limit, today or this month, whatever the
 *   period.
 */
data class SpendingReport(
    val period: SpendingPeriod,
    val from: Instant,
    val to: Instant,
    val totalMicros: Long,
    val budgetMicros: Long?,
    val totals: SpendingTotals,
    val daily: List<SpendingDay>,
    val exceeded: List<ExceededBudget> = emptyList(),
)

/**
 * The days from [first] to [last], both included.
 *
 * @param first The first day.
 * @param last The last day.
 * @return The days.
 */
internal fun daysBetween(first: LocalDate, last: LocalDate): List<LocalDate> =
    generateSequence(first) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= last }.toList()

/**
 * The UTC day of an instant.
 *
 * @param at The instant.
 * @return Its day.
 */
internal fun dayOf(at: Instant): LocalDate = at.toLocalDateTime(TimeZone.UTC).date

/**
 * The first instant of a UTC day.
 *
 * @param day The day.
 * @return Its start.
 */
internal fun startOf(day: LocalDate): Instant = day.atStartOfDayIn(TimeZone.UTC)
