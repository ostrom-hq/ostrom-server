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
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.testing.writeReadReport
import dev.ostrom.server.usage.UsageModule
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/spending` on a ledger of 1 000 000 model calls of 10 organizations over 60 days (100 000 each) and
 * 50 000 sessions: the owner's month and week, a member's month. Each keeps the read budget (ReadBench.kt); its
 * queries, plans and p95 are written to `build/reports/perf/reports.txt`.
 */
class ReportsReadsBenchTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        SecretsModule, UsageModule, BudgetsModule, ReportsModule,
    )
    private val orgs = listOf(ORG_A, ORG_B) + List(ORGS - 2) { Uuid.random() }

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter. A tenth of the
    // calls are olga's, a tenth mia's, the rest of 28 other members; half in a session, half in a launch.
    private fun usageOf(org: Uuid): String {
        val epoch = NOW.epochSeconds
        return "INSERT INTO usage_events (id, org_id, member_id, kind, name, session_id, launch_id, input_tokens, " +
            "output_tokens, cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) SELECT " +
            "gen_random_uuid(), CAST('$org' AS uuid), CASE i % 10 WHEN 0 THEN CAST('${OLGA.memberId}' AS uuid) " +
            "WHEN 1 THEN CAST('${MIA.memberId}' AS uuid) ELSE CAST(md5('m' || (i % 28)) AS uuid) END, 'MODEL', " +
            "'claude', CASE WHEN i % 2 = 0 THEN 's' ELSE 'lch' END || (i % $SESSIONS_PER_ORG), CASE WHEN i % 2 = 1 " +
            "THEN 'lch' || (i % $SESSIONS_PER_ORG) END, 10, 10, 0, 0, i % 1000, 5, 'ok', " +
            "to_timestamp($epoch) - (i % 1440) * INTERVAL '1 hour' - (i % 60) * INTERVAL '1 minute' " +
            "FROM generate_series(1, $USAGE_PER_ORG) AS i"
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
    fun theSpendingReportKeepsItsBudget() = serverTest(modules, reportsOverrides(FixedClock())) { server ->
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
        measures.forEach { it.assertWithinBudget(setOf("usage_events", "sessions_sessions")) }
    }

    private companion object {
        const val ORGS = 10
        const val USAGE_PER_ORG = 100_000
        const val SESSIONS_PER_ORG = 5_000
    }
}
