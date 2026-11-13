// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports

import run.ostrom.server.reports.domain.ActivityCursor
import run.ostrom.server.reports.domain.ActivityItem
import run.ostrom.server.reports.domain.ActivityKind
import run.ostrom.server.reports.domain.ActivityPage
import run.ostrom.server.reports.domain.ExceededBudget
import run.ostrom.server.reports.domain.SpendingDay
import run.ostrom.server.reports.domain.SpendingLine
import run.ostrom.server.reports.domain.SpendingPeriod
import run.ostrom.server.reports.domain.SpendingReport
import run.ostrom.server.reports.domain.SpendingTotals
import run.ostrom.server.reports.presentation.ActivityActorDto
import run.ostrom.server.reports.presentation.ActivityItemDto
import run.ostrom.server.reports.presentation.ExceededBudgetDto
import run.ostrom.server.reports.presentation.SpendingDayDto
import run.ostrom.server.reports.presentation.SpendingDto
import run.ostrom.server.reports.presentation.SpendingLineDto
import run.ostrom.server.reports.presentation.toDto
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ReportsPresentationTest {
    @Test
    fun theReportMapsToTheContractInDollars() {
        val line = SpendingLine("claude", "claude", 1_250_000, 2)
        val report = SpendingReport(
            period = SpendingPeriod.WEEK,
            from = NOW,
            to = NOW,
            totalMicros = 1_250_000,
            budgetMicros = 5_000_000,
            totals = SpendingTotals(listOf(line), listOf(line), listOf(line), emptyList()),
            daily = listOf(SpendingDay(LocalDate(2026, 10, 30), 1_250_000)),
        )
        val dto = SpendingLineDto("claude", "claude", 1.25, 2)
        assertEquals(
            SpendingDto(
                "week", NOW.toString(), NOW.toString(), 1.25, 5.0, listOf(dto), listOf(dto), listOf(dto),
                listOf(SpendingDayDto("2026-10-30", 1.25)),
            ),
            report.toDto(),
        )
        assertNull(report.copy(budgetMicros = null).toDto().budgetUsd)
    }

    @Test
    fun aBudgetPastItsLimitMapsToTheContract() {
        val member = Uuid.parse("00000000-0000-0000-0000-00000000000a")
        assertEquals(
            ExceededBudgetDto("member", member.toString(), "day", 0.4, hardStop = false, since = NOW.toString()),
            ExceededBudget(member, daily = true, stops = false, limitMicros = 400_000, since = NOW).toDto(),
        )
        assertEquals(
            ExceededBudgetDto("organization", null, "month", 100.0, hardStop = true, since = NOW.toString()),
            ExceededBudget(null, daily = false, stops = true, limitMicros = 100_000_000, since = NOW).toDto(),
        )
        assertEquals(
            ExceededBudgetDto("workflow", "review", "month", 3.0, hardStop = true, since = NOW.toString()),
            ExceededBudget(null, false, true, 3_000_000, NOW, workflowId = "review").toDto(),
        )
    }

    @Test
    fun eachKindHasItsSummary() {
        fun summary(kind: ActivityKind, outcome: String? = null, repo: String? = null) =
            ActivityItem("i", NOW, kind, "claude", "mia", outcome = outcome, repo = repo).toDto().summary
        val repo = "acme/api"
        assertEquals("mia started a claude session in acme/api", summary(ActivityKind.SESSION_STARTED, repo = repo))
        assertEquals("mia started a claude session", summary(ActivityKind.SESSION_STARTED))
        assertEquals("mia's claude session ended in acme/api", summary(ActivityKind.SESSION_ENDED, repo = repo))
        assertEquals("mia's claude session ended", summary(ActivityKind.SESSION_ENDED))
        assertEquals("claude asked for a yes: mia", summary(ActivityKind.GATE_REQUESTED))
        assertEquals("claude said yes to: mia", summary(ActivityKind.GATE_DECIDED, "approved"))
        assertEquals("claude said no to: mia", summary(ActivityKind.GATE_DECIDED, "rejected"))
        assertEquals("claude changed the workflow mia", summary(ActivityKind.WORKFLOW_CHANGED))
        val kinds = ActivityKind.entries.associate {
            it.wire to ActivityItem("i", NOW, it, "a", "s").toDto().actor.kind
        }
        assertEquals(
            mapOf(
                "session.started" to "agent", "session.ended" to "agent", "gate.requested" to "agent",
                "gate.decided" to "person", "workflow.changed" to "person",
            ),
            kinds,
        )
    }

    @Test
    fun anItemAndAPageMapToTheContract() {
        val item = ActivityItem(
            "session.ended:s1", NOW, ActivityKind.SESSION_ENDED, "claude", "mia",
            repo = "acme/api", costMicros = 500_000, sessionId = "s1", gateId = null,
        )
        assertEquals(
            ActivityItemDto(
                "session.ended:s1", NOW.toString(), "session.ended", ActivityActorDto("agent", "claude"),
                "mia's claude session ended in acme/api", "acme/api", 0.5, null, "s1",
            ),
            item.toDto(),
        )
        assertNull(item.copy(costMicros = null).toDto().costUsd)
        val page = ActivityPage(listOf(item), ActivityCursor(NOW, "session.ended:s1")).toDto()
        assertEquals("2026-10-30T12:00:00Z~session.ended:s1", page.next)
        assertNull(ActivityPage(emptyList(), null).toDto().next)
    }
}
