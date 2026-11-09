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
import run.ostrom.server.reports.infrastructure.SpendingRanges
import run.ostrom.server.reports.infrastructure.spendingStatement
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The spending query of server#321 answers line for line what the query before it answered (kept below as it was on
 * main, `legacySpending`): it reads only the launches the window spent in, joins them by their bytes and adds the days
 * up without joins. On a ledger of sessions in several segments of four launches, a launch and a session that spent
 * nothing, a call outside any session, a member and the organization with a budget and no spending, for the month,
 * the week and a window inside a day, the organization and each member. Neither query has a line at zero: the lines
 * come from the spending only, so a budget without spending has none, before as after.
 */
class ReportsSpendingQueryPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        SecretsModule, UsageModule, BudgetsModule, ReportsModule,
    )

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter.
    private fun ledger(): List<String> {
        val epoch = NOW.epochSeconds
        val org = "CAST('$ORG_A' AS uuid)"
        return listOf(
            // Four launches of three segments each (one root per launch), then a launch and a session that spend
            // nothing.
            "INSERT INTO sessions_sessions (org_id, id, root_id, segment, launch_id, agent, dev, repo, started_at, " +
                "last_seen_at) SELECT $org, 's' || i, 'r' || (i % 4), i / 4, 'lch' || (i % 4), " +
                "CASE WHEN i % 2 = 0 THEN 'codex' ELSE 'claude-code' END, 'dev', 'acme/r' || (i % 3), " +
                "to_timestamp($epoch) - i * INTERVAL '1 day', to_timestamp($epoch) FROM generate_series(0, 11) AS i",
            "INSERT INTO sessions_sessions (org_id, id, root_id, segment, launch_id, agent, dev, repo, started_at, " +
                "last_seen_at) VALUES ($org, 'idle', 'idle', 0, 'lch-idle', 'codex', 'dev', 'acme/idle', " +
                "to_timestamp($epoch), to_timestamp($epoch)), ($org, 'quiet', 'quiet', 0, NULL, 'codex', 'dev', " +
                "'acme/quiet', to_timestamp($epoch), to_timestamp($epoch))",
            // Olga and nobody spend, in a session, through a launch, or outside any session; Mia never does.
            "INSERT INTO usage_events (id, org_id, member_id, kind, name, session_id, launch_id, input_tokens, " +
                "output_tokens, cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) " +
                "SELECT gen_random_uuid(), $org, CASE WHEN i % 3 = 0 THEN NULL " +
                "ELSE CAST('${OLGA.memberId}' AS uuid) END, 'MODEL', 'claude', " +
                "CASE i % 4 WHEN 0 THEN 's' || (i / 4 % 12) WHEN 1 THEN 'lch' || (i / 4 % 4) " +
                "WHEN 2 THEN 'gone' END, " +
                "CASE WHEN i % 4 = 1 THEN 'lch' || (i / 4 % 4) END, 1, 1, 0, 0, " +
                "CASE WHEN i % 7 = 0 THEN 0 ELSE i END, 5, 'ok', " +
                "to_timestamp($epoch) - i * INTERVAL '47 minutes' + INTERVAL '1 hour' " +
                "FROM generate_series(0, 1200) AS i",
        )
    }

    private suspend fun TestServer.rows(statement: Statement): List<String> =
        driver.withOrganization(ORG_A) { tx ->
            tx.fetchAll(statement).getOrThrow().map { row ->
                listOf("dim", "group_key", "label", "micros", "sessions").joinToString("|") { row.get(it).asString() }
            }.sorted()
        }

    @Test
    fun theQueryAnswersWhatTheQueryBeforeItAnswered() = serverTest(modules, reportsOverrides(FixedClock())) { server ->
        server.seedPeople()
        ledger().forEach { statement -> server.driver.withOrganization(ORG_A) { it.execute(statement).getOrThrow() } }
        server.database.superuser.execute(
            "INSERT INTO budgets_limits (org_id, member_id, scope, limit_micros, updated_at) VALUES " +
                "(CAST('$ORG_A' AS uuid), CAST('${MIA.memberId}' AS uuid), 'MEMBER', 5000000, now())",
        ).getOrThrow()
        val windows = listOf(
            "the month" to (startOf(LocalDate(2026, 10, 1)) to NOW),
            "the week" to (startOf(LocalDate(2026, 10, 26)) to NOW),
            "inside one day" to (NOW - 5.hours to NOW),
            "forty days" to (NOW - 40.days to NOW),
        )
        for ((name, window) in windows) {
            for (member in listOf(null, OLGA.memberId, MIA.memberId)) server.assertSameLines(name, member, window)
        }
    }

    private suspend fun TestServer.assertSameLines(name: String, member: Uuid?, window: Pair<Instant, Instant>) {
        val (from, to) = window
        val legacy = Statement.create(legacySpending(member = member != null)).bind("org_id", ORG_A)
        member?.let { legacy.bind("member_id", it) }
        val before = rows(SpendingRanges.of(from, to).bindTo(legacy))
        val after = rows(spendingStatement(ORG_A, member, from, to))
        assertEquals(before, after, "$name, $member")
        assertTrue(after.none { it.split("|")[3] == "0" }, "$name, $member: no line at zero, before as after")
        when (member) {
            MIA.memberId -> assertTrue(after.isEmpty(), "$name: Mia has a budget and no spending")
            null -> {
                assertTrue(after.any { it.startsWith("agent|") && !it.endsWith("|0") }, "$name: sessions found")
                assertTrue(after.none { "acme/idle" in it || "acme/quiet" in it }, "$name: nothing spent there")
            }
            else -> assertTrue(after.isNotEmpty(), "$name: Olga spends")
        }
    }
}

private fun legacySpending(member: Boolean): String {
    val mine = if (member) " AND member_id = :member_id" else ""
    fun hours(from: String, to: String) = """
            SELECT org_id, member_id, session_id, launch_id,
                CAST(at AT TIME ZONE 'UTC' AS DATE) AS day, SUM(cost_micros) AS cost_micros
            FROM usage_events
            WHERE org_id = :org_id AND at >= :$from AND at < :$to AND cost_micros > 0$mine
            GROUP BY 1, 2, 3, 4, 5"""
    return """
        WITH calls AS (
            SELECT org_id, member_id, session_id, launch_id, day, cost_micros
            FROM usage_daily
            WHERE org_id = :org_id AND day >= CAST(:day_from AS DATE) AND day < CAST(:day_to AS DATE)$mine
            UNION ALL${hours("head_from", "head_to")}
            UNION ALL${hours("tail_from", "tail_to")}
        ),
        spent AS (
            SELECT agent, repo, member_id, day, spent_in, GROUPING(agent, repo, member_id, day) AS g,
                MIN(login) AS login, SUM(cost_micros) AS cost_micros
            FROM (
                SELECT c.member_id, COALESCE(s.id, l.id) AS spent_in, c.cost_micros, c.day,
                    COALESCE(s.agent, l.agent) AS agent, COALESCE(s.repo, l.repo) AS repo, u.login
                FROM calls c
                LEFT JOIN sessions_sessions s ON s.org_id = c.org_id AND s.id = c.session_id
                LEFT JOIN (
                    SELECT DISTINCT ON (f.launch_id COLLATE "C") f.launch_id, f.id, f.agent, f.repo
                    FROM sessions_sessions f WHERE f.org_id = :org_id AND f.launch_id IS NOT NULL
                    ORDER BY f.launch_id COLLATE "C", f.segment
                ) l ON s.id IS NULL AND l.launch_id = c.launch_id
                LEFT JOIN orgs_members m ON m.org_id = c.org_id AND m.id = c.member_id
                LEFT JOIN identity_users u ON u.id = m.user_id
            ) x
            GROUP BY GROUPING SETS ((agent, spent_in), (repo, spent_in), (member_id, spent_in), (day))
        )
        SELECT CASE g WHEN 7 THEN 'agent' WHEN 11 THEN 'repo' WHEN 13 THEN 'person'
                ELSE 'day' END AS dim,
            CASE g WHEN 7 THEN COALESCE(agent, '') WHEN 11 THEN COALESCE(repo, '')
                WHEN 13 THEN COALESCE(CAST(member_id AS TEXT), '') ELSE to_char(day, 'YYYY-MM-DD')
                END AS group_key,
            CASE g WHEN 7 THEN COALESCE(agent, '') WHEN 11 THEN COALESCE(repo, '')
                WHEN 13 THEN COALESCE(MIN(login), CAST(member_id AS TEXT), '') ELSE '' END AS label,
            CAST(SUM(cost_micros) AS BIGINT) AS micros,
            CASE WHEN g = 14 THEN 0 ELSE COUNT(spent_in) END AS sessions
        FROM spent GROUP BY g, agent, repo, member_id, day
    """.trimIndent()

}
