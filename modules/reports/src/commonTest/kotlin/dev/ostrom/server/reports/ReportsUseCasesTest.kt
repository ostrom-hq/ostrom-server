// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports

import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.reports.domain.ActivityCursor
import dev.ostrom.server.reports.domain.ActivityItem
import dev.ostrom.server.reports.domain.ActivityKind
import dev.ostrom.server.reports.domain.GetSpendingUseCaseImpl
import dev.ostrom.server.reports.domain.InvalidCursorException
import dev.ostrom.server.reports.domain.InvalidPeriodException
import dev.ostrom.server.reports.domain.ListActivityUseCaseImpl
import dev.ostrom.server.reports.domain.SpendingDay
import dev.ostrom.server.reports.domain.SpendingLine
import dev.ostrom.server.reports.domain.SpendingPeriod
import dev.ostrom.server.reports.domain.SpendingTotals
import dev.ostrom.server.reports.domain.Viewer
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ReportsUseCasesTest {
    private val member = Uuid.random()
    private val repository = FakeReportsRepository()
    private val clock = FixedClock()

    @Test
    fun ownersAdminsAndBillingSeeTheWholeOrganizationTheOthersTheirOwn() {
        for (role in OrgRole.entries) {
            val viewer = Viewer.of(OrgPrincipal(ORG_A, member, role))
            val whole = role == OrgRole.OWNER || role == OrgRole.ADMIN || role == OrgRole.BILLING
            assertEquals(Viewer(ORG_A, member, whole), viewer, role.name)
        }
    }

    @Test
    fun thePeriodIsTheMonthUnlessTheWeekIsAsked() {
        assertEquals(SpendingPeriod.MONTH, SpendingPeriod.of(null))
        assertEquals(SpendingPeriod.MONTH, SpendingPeriod.of("month"))
        assertEquals(SpendingPeriod.WEEK, SpendingPeriod.of("week"))
        assertFailsWith<InvalidPeriodException> { SpendingPeriod.of("year") }
    }

    @Test
    fun theMonthReportCountsFromTheFirstOfTheMonthAgainstTheOrganizationsBudget() = runTest {
        repository.totals = SpendingTotals(
            byAgent = listOf(SpendingLine("codex", "codex", 5, 1), SpendingLine("claude", "claude", 9, 2)),
            byRepo = listOf(SpendingLine("b", "b", 7, 1), SpendingLine("a", "a", 7, 1)),
            byPerson = listOf(SpendingLine("", "Organization", 1, 0), SpendingLine("m", "mia", 13, 3)),
            byDay = listOf(SpendingDay(LocalDate(2026, 10, 2), 4), SpendingDay(LocalDate(2026, 10, 30), 10)),
        )
        val report = GetSpendingUseCaseImpl(repository, FakeBudgets(100, 20), clock)(
            Viewer(ORG_A, member, wholeOrganization = true),
            SpendingPeriod.MONTH,
        )

        val first = Instant.parse("2026-10-01T00:00:00Z")
        assertEquals(listOf<Any?>(ORG_A, null, first, NOW), repository.spendingAsked.single())
        assertEquals(first, report.from)
        assertEquals(NOW, report.to)
        assertEquals(14L, report.totalMicros)
        assertEquals(100L, report.budgetMicros)
        assertEquals(listOf("claude", "codex"), report.totals.byAgent.map { it.key })
        // Equal costs: by key.
        assertEquals(listOf("a", "b"), report.totals.byRepo.map { it.key })
        assertEquals(listOf("m", ""), report.totals.byPerson.map { it.key })
        assertEquals(30, report.daily.size)
        assertEquals(SpendingDay(LocalDate(2026, 10, 1), 0), report.daily.first())
        assertEquals(SpendingDay(LocalDate(2026, 10, 2), 4), report.daily[1])
        assertEquals(SpendingDay(LocalDate(2026, 10, 30), 10), report.daily.last())
    }

    @Test
    fun theWeekReportIsTheLastSevenDaysOfTheMembersOwnSpendingWithoutBudget() = runTest {
        val report = GetSpendingUseCaseImpl(repository, FakeBudgets(100, 20), clock)(
            Viewer(ORG_A, member, wholeOrganization = false),
            SpendingPeriod.WEEK,
        )

        val first = Instant.parse("2026-10-24T00:00:00Z")
        assertEquals(listOf<Any?>(ORG_A, member, first, NOW), repository.spendingAsked.single())
        assertNull(report.budgetMicros)
        assertEquals(0L, report.totalMicros)
        assertEquals((24..30).map { SpendingDay(LocalDate(2026, 10, it), 0) }, report.daily)
    }

    @Test
    fun aMembersMonthIsMeasuredAgainstTheirOwnBudget() = runTest {
        val report = GetSpendingUseCaseImpl(repository, FakeBudgets(100, 20), clock)(
            Viewer(ORG_A, member, wholeOrganization = false),
            SpendingPeriod.MONTH,
        )
        assertEquals(20L, report.budgetMicros)
    }

    @Test
    fun aCursorReadsBackWhatAPageWrote() {
        val cursor = ActivityCursor(NOW, "gate.decided:42")
        assertEquals("2026-10-30T12:00:00Z~gate.decided:42", cursor.wire)
        assertEquals(cursor, ActivityCursor.of(cursor.wire))
        assertFailsWith<InvalidCursorException> { ActivityCursor.of("yesterday~x") }
        assertFailsWith<InvalidCursorException> { ActivityCursor.of("2026-10-30T12:00:00Z~") }
        assertFailsWith<InvalidCursorException> { ActivityCursor.of("2026-10-30T12:00:00Z") }
    }

    @Test
    fun theFeedPagesByFiftyAndSaysWhereTheNextPageStarts() = runTest {
        repository.items = (0 until 51).map { item(it) }
        val listActivity = ListActivityUseCaseImpl(repository)
        val viewer = Viewer(ORG_A, member, wholeOrganization = false)
        val before = ActivityCursor(NOW, "x")

        val full = listActivity(viewer, before, "acme/api")
        assertEquals(listOf<Any?>(ORG_A, before, "acme/api", 51), repository.activityAsked.single())
        assertEquals(50, full.items.size)
        assertEquals(item(49).cursor, full.next)

        repository.items = (0 until 50).map { item(it) }
        val last = listActivity(viewer, null, null)
        assertEquals(50, last.items.size)
        assertNull(last.next)
    }

    private fun item(index: Int) = ActivityItem(
        id = "session.started:s$index",
        at = NOW - index.minutes,
        kind = ActivityKind.SESSION_STARTED,
        actor = "mia",
        subject = "claude",
    )
}
