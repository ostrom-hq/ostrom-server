// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.reports

import run.ostrom.server.budgets.BudgetsModule
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.reports.domain.startOf
import run.ostrom.server.reports.infrastructure.ReportsQueries
import run.ostrom.server.reports.infrastructure.SpendingRanges
import run.ostrom.server.reports.infrastructure.spendingStatement
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import run.ostrom.server.usage.domain.UsageRepository
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The spending report read from the daily totals (`usage_daily`) gives, line for line, what it gives when it adds up
 * every call of the window (the same query with no whole day), on one ledger: two members and nobody, a
 * session, a launch through the gateway, spending outside any session, calls that cost nothing, a call after the end
 * of the window, over 40 days; for the month, the week, today alone, a window that starts and one that ends inside a
 * day, the organization and one member; and again after calls are deleted and the retention purge has run.
 */
class ReportsDailyTotalsPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        SecretsModule, UsageModule, BudgetsModule, ReportsModule,
    )

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter.
    private fun ledger(org: Uuid): List<String> {
        val epoch = NOW.epochSeconds
        val member = "CASE i % 3 WHEN 0 THEN CAST('${OLGA.memberId}' AS uuid) WHEN 1 THEN " +
            "CAST('${MIA.memberId}' AS uuid) END"
        return listOf(
            "INSERT INTO sessions_sessions (org_id, id, root_id, segment, launch_id, agent, dev, repo, started_at, " +
                "last_seen_at) SELECT CAST('$org' AS uuid), 's' || i, 's' || i, 0, 'lch' || (i % 4), " +
                "CASE WHEN i % 2 = 0 THEN 'codex' ELSE 'claude-code' END, 'dev', 'acme/r' || (i % 3), " +
                "to_timestamp($epoch) - i * INTERVAL '1 day', to_timestamp($epoch) FROM generate_series(0, 9) AS i",
            // Two passes over the same keys: the second adds to the totals of the first.
            *Array(2) { pass ->
                "INSERT INTO usage_events (id, org_id, member_id, kind, name, session_id, launch_id, input_tokens, " +
                    "output_tokens, cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) " +
                    "SELECT gen_random_uuid(), CAST('$org' AS uuid), $member, 'MODEL', 'claude', CASE i % 4 " +
                    "WHEN 0 THEN 's' || (i % 10) WHEN 1 THEN 'lch' || (i % 4) WHEN 2 THEN 'gone' END, CASE WHEN " +
                    "i % 4 = 1 THEN 'lch' || (i % 4) END, 1, 1, 0, 0, CASE WHEN i % 7 = 0 THEN 0 ELSE i + $pass END, " +
                    "5, 'ok', to_timestamp($epoch) - i * INTERVAL '47 minutes' + INTERVAL '1 hour' " +
                    "FROM generate_series(0, 1200) AS i"
            },
        )
    }

    private suspend fun TestServer.rows(statement: Statement): List<String> =
        driver.withOrganization(ORG_A) { tx ->
            tx.fetchAll(statement).getOrThrow().map { row ->
                listOf("dim", "group_key", "label", "micros", "sessions").joinToString("|") { row.get(it).asString() }
            }.sorted()
        }

    private suspend fun TestServer.assertSameReport(step: String) {
        val windows = listOf(
            "the month" to (startOf(LocalDate(2026, 10, 1)) to NOW),
            "the week" to (startOf(LocalDate(2026, 10, 26)) to NOW),
            "today" to (startOf(LocalDate(2026, 10, 30)) to NOW),
            "from inside a day" to (NOW - 10.days - 5.hours to NOW),
            "to the end of a day" to (startOf(LocalDate(2026, 10, 1)) to startOf(LocalDate(2026, 10, 20))),
            "inside one day" to (NOW - 5.hours to NOW - 30.minutes),
        )
        for ((name, window) in windows) {
            for (member in listOf(null, MIA.memberId)) {
                val (from, to) = window
                // The same query with no whole day: every hour of the window added up from the calls.
                val calls = Statement.create(ReportsQueries.spending(member = member != null)).bind("org_id", ORG_A)
                member?.let { calls.bind("member_id", it) }
                val expected = rows(SpendingRanges.allHours(from, to).bindTo(calls))
                assertTrue(expected.isNotEmpty(), "$step, $name: the window has spending")
                assertEquals(expected, rows(spendingStatement(ORG_A, member, from, to)), "$step, $name, $member")
            }
        }
    }

    @Test
    fun theReportFromTheDailyTotalsIsTheReportFromTheCalls() =
        serverTest(modules, reportsOverrides(FixedClock())) { server ->
            server.seedPeople()
            for (statement in ledger(ORG_A) + ledger(ORG_B)) {
                val org = if ("'$ORG_A'" in statement) ORG_A else ORG_B
                server.driver.withOrganization(org) { it.execute(statement).getOrThrow() }
            }
            server.assertSameReport("a ledger written in two passes")

            server.driver.withOrganization(ORG_A) {
                it.execute("DELETE FROM usage_events WHERE cost_micros % 5 = 0").getOrThrow()
            }
            server.assertSameReport("after calls are deleted")

            assertTrue(server.koin.get<UsageRepository>().purge(NOW - 20.days, 10_000) > 0)
            server.assertSameReport("after the retention purge")
        }

    @Test
    fun aWindowIsCutIntoWholeDaysAndTheHoursAround() {
        val day = LocalDate(2026, 10, 20)
        val next = LocalDate(2026, 10, 21)
        val noon = startOf(day) + 12.hours
        assertEquals(
            SpendingRanges(day to next, startOf(day) to startOf(day), startOf(next) to noon + 1.days),
            SpendingRanges.of(startOf(day), noon + 1.days),
            "From the start of a day",
        )
        val late = startOf(next) + 1.hours
        assertEquals(
            SpendingRanges(next to next, noon to late, late to late),
            SpendingRanges.of(noon, late),
            "From inside a day, no whole day",
        )
        assertEquals(
            SpendingRanges(day to day, noon to noon + 1.hours, noon + 1.hours to noon + 1.hours),
            SpendingRanges.of(noon, noon + 1.hours),
            "Inside one day: all hours",
        )
    }
}
