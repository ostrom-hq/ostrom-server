// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports

import dev.ostrom.server.budgets.BudgetsModule
import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.reports.infrastructure.ReportsQueries
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.UsageModule
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * The reports' reads under `EXPLAIN ANALYZE`, in a transaction scoped to the organization like the repository's, over
 * tables where the organization is a small part of the rows: the ledger, the sessions and the gates are each read
 * through an index, never a scan of the table. The plans are written to `build/reports/explain/reports.txt`, uploaded
 * with the CI's reports. The workflow decisions are a handful per organization: their plan is written, not asserted.
 */
class ReportsQueryPlansTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        SecretsModule, UsageModule, BudgetsModule, ReportsModule,
    )

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter.
    private suspend fun TestServer.seed(org: Uuid, scale: Int) = driver.withOrganization(org) { tx ->
        val epoch = NOW.epochSeconds
        val sql = listOf(
            // Half the events are the launcher's (a session's id), half the gateway's (a launch's id, twice).
            "INSERT INTO usage_events (id, org_id, member_id, kind, name, session_id, launch_id, input_tokens, " +
                "output_tokens, cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) SELECT " +
                "gen_random_uuid(), CAST('$org' AS uuid), CAST(md5('m' || (i % 30)) AS uuid), 'MODEL', 'claude', " +
                "CASE WHEN i % 2 = 0 THEN 's' ELSE 'lch' END || (i % ${scale / 4}), CASE WHEN i % 2 = 1 THEN " +
                "'lch' || (i % ${scale / 4}) END, 10, 10, 0, 0, i % 1000, 5, 'ok', " +
                "to_timestamp($epoch) - (i % 1440) * INTERVAL '1 hour' FROM generate_series(1, ${scale * 4}) AS i",
            "INSERT INTO sessions_sessions (org_id, id, root_id, segment, launch_id, agent, dev, repo, started_at, " +
                "ended_at, cost_usd) SELECT CAST('$org' AS uuid), 's' || i, 's' || i, 0, 'lch' || i, 'claude-code', " +
                "'dev' || (i % 30), " +
                "'acme/r' || (i % 20), to_timestamp($epoch) - i * INTERVAL '1 hour', CASE WHEN i % 2 = 0 THEN " +
                "to_timestamp($epoch) - i * INTERVAL '1 hour' + INTERVAL '30 minutes' END, 0.5 " +
                "FROM generate_series(1, $scale) AS i",
            "INSERT INTO gates_gates (id, org_id, gate_key, subject, label, mode, status, asked_by, asked_via, " +
                "session_ids, launch_ids, shown_digest, evidence, decided_by, decided_at, created_at) SELECT " +
                "gen_random_uuid(), CAST('$org' AS uuid), 'merge', 'pr', 'Merge', 'ENFORCE', CASE WHEN i % 3 = 0 " +
                "THEN 'PENDING' ELSE 'APPROVED' END, '[\"claude\"]', 'cli', '[]', '[]', 'd', '{}', CASE WHEN " +
                "i % 3 = 0 THEN NULL ELSE 'rev' END, CASE WHEN i % 3 = 0 THEN NULL ELSE to_timestamp($epoch) - i * " +
                "INTERVAL '1 hour' + INTERVAL '5 minutes' END, to_timestamp($epoch) - i * INTERVAL '1 hour' " +
                "FROM generate_series(1, $scale) AS i",
        )
        for (statement in sql) tx.execute(statement).getOrThrow()
    }

    @Test
    fun theReportsReadsUseTheirIndexes() = serverTest(modules, reportsOverrides(FixedClock())) { server ->
        server.seedPeople()
        server.seed(ORG_B, 8_000)
        server.seed(ORG_A, 800)
        for (table in listOf("usage_events", "sessions_sessions", "gates_gates")) {
            // Statistics only, which no request writes: the serving role may not vacuum (#68).
            server.database.superuser.execute("VACUUM ANALYZE $table").getOrThrow()
        }
        val month = NOW - 30.days
        val cursorAt = NOW - 10.days
        val plans = linkedMapOf(
            "spending of the organization, 30 days" to server.explain(ReportsQueries.spending(member = false)) {
                bind("from", month).bind("to", NOW)
            },
            "spending of a member, 7 days" to server.explain(ReportsQueries.spending(member = true)) {
                bind("from", NOW - 7.days).bind("to", NOW).bind("member_id", MIA.memberId)
            },
            "newest page of the feed" to server.explain(ReportsQueries.activity(cursor = false, repo = false)) {
                bind("limit", 51)
            },
            "next page of the feed" to server.explain(ReportsQueries.activity(cursor = true, repo = false)) {
                bind("limit", 51).bind("before_at", cursorAt).bind("before_id", "session.started:s240")
            },
            "a repository's page" to server.explain(ReportsQueries.activity(cursor = true, repo = true)) {
                bind("limit", 51).bind("before_at", cursorAt).bind("before_id", "x").bind("repo", "acme/r3")
            },
        )
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/reports.txt")).buffered().use { it.writeString(report) }
        for ((name, plan) in plans) {
            for (table in listOf("usage_events", "sessions_sessions", "gates_gates")) {
                assertFalse("Seq Scan on $table" in plan, "$name scans $table:\n$plan")
            }
        }
    }

    /** The plan of [sql] in org A, scoped like the repository's reads; `:org_id` is bound, [bind] adds the rest. */
    private suspend fun TestServer.explain(sql: String, bind: Statement.() -> Statement): String {
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", ORG_A).bind()
        return driver.withOrganization(ORG_A) { tx ->
            tx.fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
    }
}
