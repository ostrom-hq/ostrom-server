// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.CronSchedule
import run.ostrom.server.automations.domain.InvalidAutomationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class CronTest {
    private val paris = TimeZone.of("Europe/Paris")

    private fun at(text: String) = Instant.parse(text)

    private fun next(cron: String, after: String, zone: TimeZone = TimeZone.UTC) =
        CronSchedule.parse(cron).next(at(after), zone)

    @Test
    fun fieldsTakeStarsListsRangesStepsAndNames() {
        val schedule = CronSchedule.parse("  */15 9-17/4 1,15 JAN-mar mon-FRI ")
        assertEquals(listOf(0, 15, 30, 45), schedule.minutes)
        assertEquals(listOf(9, 13, 17), schedule.hours)
        assertEquals(setOf(1, 2, 3), schedule.months)
        assertEquals(setOf(1, 15), schedule.days.ofMonth)
        assertEquals(setOf(1, 2, 3, 4, 5), schedule.days.ofWeek)
        assertEquals(listOf(10, 15, 20), CronSchedule.parse("10/5 0 * * *").minutes.take(3))
        assertEquals(setOf(0), CronSchedule.parse("0 0 * * 7").days.ofWeek)
        assertEquals(setOf(0, 6), CronSchedule.parse("0 0 * * SUN,sat").days.ofWeek)
    }

    @Test
    fun malformedOrImpossibleCronsAreRefused() {
        val refused = listOf(
            "* * * *",
            "0 0 * * * *",
            "x 0 * * *",
            "60 0 * * *",
            "0 24 * * *",
            "0 0 0 * *",
            "0 0 * 13 *",
            "0 0 * * 8",
            "0 0 * FOO *",
            "*/0 0 * * *",
            "*/60 0 * * *",
            "10-5 0 * * *",
            "1-2-3 0 * * *",
            "0 0 30 2 *",
            "* * * * *",
            "0,3 * * * *",
            "2,58 7,8 * * *",
            "2,58 0,23 * * *",
        )
        for (cron in refused) assertFailsWith<InvalidAutomationException>(cron) { CronSchedule.parse(cron) }
        // Two hours apart, a wrap from :58 to :02 is never consecutive.
        assertEquals(listOf(2, 58), CronSchedule.parse("2,58 7,9 * * *").minutes)
        assertEquals(listOf(2, 58), CronSchedule.parse("2,58 7 * * *").minutes)
    }

    @Test
    fun theNextSlotIsStrictlyAfter() {
        assertEquals(at("2026-10-30T21:00:00Z"), next("0 21 * * *", "2026-10-30T20:59:59Z"))
        assertEquals(at("2026-10-31T21:00:00Z"), next("0 21 * * *", "2026-10-30T21:00:00Z"))
        assertEquals(at("2026-10-30T10:15:00Z"), next("*/15 * * * *", "2026-10-30T10:00:00Z"))
        assertEquals(at("2028-02-29T00:00:00Z"), next("0 0 29 2 *", "2026-10-30T00:00:00Z"))
        val schedule = CronSchedule.parse("0 9 * * MON")
        assertEquals(
            listOf(at("2026-11-02T09:00:00Z"), at("2026-11-09T09:00:00Z")),
            schedule.upcoming(at("2026-10-30T00:00:00Z"), TimeZone.UTC, 2),
        )
    }

    @Test
    fun bothRestrictedDayFieldsMatchEitherWayAndAStarStepNarrowsBoth() {
        // The 13th, or any Friday.
        val either = CronSchedule.parse("0 0 13 * FRI")
        assertTrue(either.runsOn(LocalDate(2026, 11, 13)))
        assertTrue(either.runsOn(LocalDate(2026, 11, 6)))
        assertFalse(either.runsOn(LocalDate(2026, 11, 7)))
        // A day field starting with * makes both match together: odd days that are Fridays.
        val both = CronSchedule.parse("0 0 */2 * FRI")
        assertTrue(both.runsOn(LocalDate(2026, 11, 13)))
        assertFalse(both.runsOn(LocalDate(2026, 11, 6)))
        assertFalse(both.runsOn(LocalDate(2026, 11, 3)))
        assertFalse(CronSchedule.parse("0 0 * 12 *").runsOn(LocalDate(2026, 11, 1)))
        assertTrue(CronSchedule.parse("0 0 1 * *").runsOn(LocalDate(2026, 11, 1)))
    }

    @Test
    fun aSlotInTheSpringGapRunsAfterItAndAnAutumnRepeatRunsOnce() {
        // 29 March 2026, Paris jumps from 02:00 to 03:00 (01:00 UTC): 02:30 runs at 03:30, 01:30 UTC.
        assertEquals(at("2026-03-29T01:30:00Z"), next("30 2 * * *", "2026-03-28T12:00:00Z", paris))
        // The earliest instant wins, whatever the local order: 02:00 and 03:00 both run at 01:00 UTC, once.
        val night = CronSchedule.parse("0,30 2,3 * * *")
        assertEquals(
            listOf(at("2026-03-29T01:00:00Z"), at("2026-03-29T01:30:00Z"), at("2026-03-30T00:00:00Z")),
            night.upcoming(at("2026-03-28T12:00:00Z"), paris, 3),
        )
        // 25 October 2026, Paris repeats 02:00-03:00: 02:30 runs at its first occurrence (00:30 UTC), not again.
        assertEquals(at("2026-10-25T00:30:00Z"), next("30 2 * * *", "2026-10-24T12:00:00Z", paris))
        assertEquals(at("2026-10-26T01:30:00Z"), next("30 2 * * *", "2026-10-25T00:30:00Z", paris))
        assertEquals(at("2026-10-26T01:30:00Z"), next("30 2 * * *", "2026-10-25T01:10:00Z", paris))
    }

    @Test
    fun aDayThatOnlySomeMonthsHaveIsFoundInTheNextYear() {
        // 31 April never exists, 31 May does.
        val schedule = CronSchedule.parse("0 0 31 4,5 *")
        assertEquals(at("2027-05-31T00:00:00Z"), schedule.next(at("2026-06-01T00:00:00Z"), TimeZone.UTC))
    }
}
