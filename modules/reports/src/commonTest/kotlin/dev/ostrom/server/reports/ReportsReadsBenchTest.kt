// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports

import dev.ostrom.server.budgets.BudgetsModule
import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.testing.assertNoScan
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.writeReadReport
import dev.ostrom.server.usage.UsageModule
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/spending` on a ledger of 1 000 000 model calls of 10 organizations (100 000 each) in 50 000
 * sessions of 20 calls, one session every 15 minutes over 52 days: the owner's month and week, a member's month.
 * Each reads the ledger through its index, never a sequential scan of it or of the sessions, and no step of its plan
 * runs once per call; its queries, plans and p95 are written to `build/reports/perf/reports.txt`. The p95 is not
 * held to the read budget (ReadBench.kt) yet: the month sums 56 000 calls of the ledger at each read, and only a
 * daily total kept by the usage module brings it under 50 ms.
 */
class ReportsReadsBenchTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        SecretsModule, UsageModule, BudgetsModule, ReportsModule,
    )
    private val orgs = listOf(ORG_A, ORG_B) + List(ORGS - 2) { Uuid.random() }

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter. A session makes
    // 20 calls in its first 20 minutes, by its member: a tenth of the sessions are olga's, a tenth mia's, the rest of
    // 28 other members; half are the launcher's (the session's id), half the gateway's (the launch's id, twice).
    private fun usageOf(org: Uuid): String {
        val epoch = NOW.epochSeconds
        val session = "(i / $CALLS_PER_SESSION)"
        return "INSERT INTO usage_events (id, org_id, member_id, kind, name, session_id, launch_id, input_tokens, " +
            "output_tokens, cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) SELECT " +
            "gen_random_uuid(), CAST('$org' AS uuid), CASE $session % 10 WHEN 0 THEN CAST('${OLGA.memberId}' AS " +
            "uuid) WHEN 1 THEN CAST('${MIA.memberId}' AS uuid) ELSE CAST(md5('m' || ($session % 28)) AS uuid) END, " +
            "'MODEL', 'claude', CASE WHEN $session % 2 = 0 THEN 's' ELSE 'lch' END || $session, CASE WHEN " +
            "$session % 2 = 1 THEN 'lch' || $session END, 10, 10, 0, 0, i % 1000, 5, 'ok', " +
            "to_timestamp($epoch) - $session * INTERVAL '15 minutes' - INTERVAL '20 minutes' + " +
            "(i % $CALLS_PER_SESSION) * INTERVAL '1 minute' FROM generate_series(0, $USAGE_PER_ORG - 1) AS i"
    }

    private fun sessionsOf(org: Uuid): String {
        val epoch = NOW.epochSeconds
        return "INSERT INTO sessions_sessions (org_id, id, root_id, segment, launch_id, agent, dev, repo, " +
            "started_at, ended_at, cost_usd) SELECT CAST('$org' AS uuid), 's' || i, 's' || i, 0, 'lch' || i, " +
            "CASE WHEN i % 3 = 0 THEN 'codex' ELSE 'claude-code' END, 'dev' || (i % 30), 'acme/r' || (i % 20), " +
            "to_timestamp($epoch) - i * INTERVAL '15 minutes', to_timestamp($epoch) - i * INTERVAL '15 minutes' + " +
            "INTERVAL '10 minutes', 0.5 FROM generate_series(0, ${SESSIONS_PER_ORG - 1}) AS i"
    }

    @Test
    fun theSpendingReportKeepsItsBudget() = benchTest(modules, reportsOverrides(FixedClock())) { server ->
        server.seedPeople()
        val fixtures = server.database.superuser
        orgs.forEach { org ->
            fixtures.execute(usageOf(org)).getOrThrow()
            fixtures.execute(sessionsOf(org)).getOrThrow()
        }
        fixtures.execute("VACUUM ANALYZE usage_events").getOrThrow()
        fixtures.execute("VACUUM ANALYZE sessions_sessions").getOrThrow()

        val measures = listOf(
            Triple("GET /api/v1/spending (owner, month)", "olga", "/api/v1/spending"),
            Triple("GET /api/v1/spending?period=week (owner)", "olga", "/api/v1/spending?period=week"),
            Triple("GET /api/v1/spending (member, month)", "mia", "/api/v1/spending"),
        ).map { (name, token, path) -> server.measureRead(name) { get(path) { bearerAuth(token) } } }
        writeReadReport("reports", measures)
        measures.forEach { measure ->
            measure.assertNoScan(setOf("usage_events", "sessions_sessions"))
            // The ledger is added up before the joins: no step of the plan runs once per call of the window (the
            // session lookup by launch did before the sums), only once per session and day.
            val loops = measure.plans.flatMap { plan -> LOOPS.findAll(plan).map { it.groupValues[1].toInt() } }
            assertTrue(loops.all { it < MAX_LOOPS }, "${measure.name} loops $loops:\n${measure.describe()}")
        }
    }

    private companion object {
        val LOOPS = Regex("loops=([0-9]+)")
        const val MAX_LOOPS = 10_000
        const val ORGS = 10
        const val USAGE_PER_ORG = 100_000
        const val SESSIONS_PER_ORG = 5_000
        const val CALLS_PER_SESSION = USAGE_PER_ORG / SESSIONS_PER_ORG
    }
}
