// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A five-field cron schedule (minute, hour, day of month, month, day of week), read in an IANA time zone.
 *
 * Fields take `*`, lists, ranges and steps (`1-5/2`, `10/5`, or a star then `/15`), the names `JAN`–`DEC` and
 * `SUN`–`SAT`, and Sunday as `0` or `7`. When both the day of month and the day of week are restricted (neither
 * starts with `*`), either matches, as in classic cron.
 *
 * A slot inside a daylight-saving gap runs at the first valid time after it (02:30 runs at 03:30 the night the clocks
 * jump from 02:00 to 03:00); a local time repeated in autumn runs once, at its first occurrence.
 *
 * @property minutes The minutes of an hour it runs at, sorted.
 * @property hours The hours of a day, sorted.
 * @property months The months.
 * @property days The days it runs on.
 */
class CronSchedule private constructor(
    val minutes: List<Int>,
    val hours: List<Int>,
    val months: Set<Int>,
    val days: CronDays,
) {
    /** Whether the schedule runs on [date]. */
    fun runsOn(date: LocalDate): Boolean = date.month.number in months && days.match(date)

    /**
     * The first slot strictly after [after], in [zone].
     *
     * @return The instant, or null when the schedule never runs again (a 30 February).
     */
    fun next(after: Instant, zone: TimeZone): Instant? {
        val start = after.toLocalDateTime(zone)
        var date = start.date
        repeat(SEARCH_DAYS) {
            if (runsOn(date)) {
                val slot = hours.asSequence()
                    .flatMap { hour -> minutes.asSequence().map { LocalDateTime(date, LocalTime(hour, it)) } }
                    .map { it.toInstant(zone) }
                    .filter { it > after }
                    .minOrNull()
                if (slot != null) return slot
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return null
    }

    /**
     * The next [count] slots after [after].
     *
     * @return At most [count] instants, fewer when the schedule ends.
     */
    fun upcoming(after: Instant, zone: TimeZone, count: Int): List<Instant> =
        generateSequence(next(after, zone)) { next(it, zone) }.take(count).toList()

    companion object {
        /** The least time between two slots. */
        val MIN_SPACING = 5.minutes

        private const val DAYS_IN_WEEK = 7

        /** Eight years and a day: a 29 February on a Monday comes back within 28 years, rarely more than 8. */
        private const val SEARCH_DAYS = 8 * 366 + 1

        private const val LAST_MINUTE = 59
        private const val LAST_HOUR = 23
        private const val LAST_DAY = 31
        private const val LAST_MONTH = 12
        private const val LAST_WEEKDAY = 7

        private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private val WEEKDAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

        /**
         * Reads a schedule.
         *
         * @throws InvalidAutomationException When the text is not five valid fields, never runs, or runs twice within
         *   [MIN_SPACING].
         */
        fun parse(text: String): CronSchedule {
            val fields = text.trim().split(Regex("\\s+"))
            requireAutomation(fields.size == FIELDS) { "A cron has five fields: minute hour day month weekday." }
            val schedule = CronSchedule(
                minutes = CronField.read(fields[0], 0..LAST_MINUTE, emptyList()).sorted(),
                hours = CronField.read(fields[1], 0..LAST_HOUR, emptyList()).sorted(),
                months = CronField.read(fields[3], 1..LAST_MONTH, MONTHS),
                days = CronDays(
                    ofMonth = CronField.read(fields[2], 1..LAST_DAY, emptyList()),
                    ofWeek = CronField.read(fields[4], 0..LAST_WEEKDAY, WEEKDAYS).map { it % DAYS_IN_WEEK }.toSet(),
                    anyOfMonth = fields[2].startsWith("*"),
                    anyOfWeek = fields[4].startsWith("*"),
                ),
            )
            requireAutomation(schedule.next(EPOCH, TimeZone.UTC) != null) { "This cron never runs." }
            requireAutomation(schedule.spaced()) { "Two runs of a cron must be at least 5 minutes apart." }
            return schedule
        }

        private const val FIELDS = 5
        private const val MINUTES_IN_HOUR = 60
        private const val HOURS_IN_DAY = 24
        private val EPOCH = Instant.fromEpochSeconds(0)
    }

    /** Whether two consecutive slots are always at least [MIN_SPACING] apart (in local time). */
    private fun spaced(): Boolean {
        val spacing = MIN_SPACING.inWholeMinutes
        val withinHour = minutes.zipWithNext().all { (a, b) -> b - a >= spacing }
        val acrossHours = minutes.first() + MINUTES_IN_HOUR - minutes.last() >= spacing
        val hourSet = hours.toSet()
        val consecutiveHours = hours.any { (it + 1) % HOURS_IN_DAY in hourSet }
        return withinHour && (acrossHours || !consecutiveHours)
    }
}

/**
 * The days of a cron.
 *
 * @property ofMonth The days of a month.
 * @property ofWeek The days of the week, Sunday being 0.
 * @property anyOfMonth Whether the day-of-month field starts with `*`.
 * @property anyOfWeek Whether the day-of-week field starts with `*`.
 */
data class CronDays(
    val ofMonth: Set<Int>,
    val ofWeek: Set<Int>,
    val anyOfMonth: Boolean,
    val anyOfWeek: Boolean,
) {
    /** Whether [date] is one of these days: when both fields are restricted, either matches (classic cron). */
    fun match(date: LocalDate): Boolean {
        val byMonth = date.day in ofMonth
        val byWeek = date.dayOfWeek.isoDayNumber % DAYS_IN_WEEK in ofWeek
        return if (anyOfMonth || anyOfWeek) byMonth && byWeek else byMonth || byWeek
    }

    private companion object {
        const val DAYS_IN_WEEK = 7
    }
}
