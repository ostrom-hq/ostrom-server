// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports

import run.ostrom.server.budgets.BudgetsModule
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.reports.domain.ActivityCursor
import run.ostrom.server.reports.domain.ActivityItem
import run.ostrom.server.reports.domain.ReportsRepository
import run.ostrom.server.reports.domain.startOf
import run.ostrom.server.reports.infrastructure.SpendingRanges
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import java.time.OffsetDateTime
import kotlin.time.toKotlinInstant
import kotlinx.datetime.LocalDate
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * The reports' Exposed reads answer what their SQL answered ([ReportsSqlBeforeExposed]), on many rows and many ties:
 * sessions, gates and workflow decisions sharing their times, ids in both cases, persons who decided as members, as
 * strangers and as plain names, and a ledger whose sessions, launches, members and days share their totals.
 */
class ReportsExposedOraclePostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        SecretsModule, UsageModule, BudgetsModule, ReportsModule,
    )

    // Ten rows of each source share each time, so every page ends inside a tie.
    private fun feedOf(org: Uuid): List<String> {
        val epoch = NOW.epochSeconds
        val at = "to_timestamp($epoch) - (i / 10) * INTERVAL '1 hour'"
        return listOf(
            "INSERT INTO sessions_sessions (org_id, id, root_id, segment, launch_id, agent, dev, repo, started_at, " +
                "ended_at, cost_usd) SELECT CAST('$org' AS uuid), CASE WHEN i % 4 = 0 THEN 'S' ELSE 's' END || " +
                "CASE WHEN i % 5 = 0 THEN '-' ELSE '_' END || i, 'r' || i, 0, 'lch' || (i % 7), " +
                "CASE WHEN i % 3 = 0 THEN 'codex' ELSE 'claude-code' END, 'dev' || (i % 4), " +
                "CASE WHEN i % 6 = 0 THEN NULL ELSE 'acme/r' || (i % 3) END, $at, " +
                "CASE WHEN i % 2 = 0 THEN $at END, CASE WHEN i % 2 = 0 THEN 0.1 * i END " +
                "FROM generate_series(1, 120) AS i",
            "INSERT INTO gates_gates (id, org_id, gate_key, subject, label, mode, status, asked_by, asked_via, " +
                "session_ids, launch_ids, shown_digest, evidence, decided_by, decided_at, created_at) SELECT " +
                "gen_random_uuid(), CAST('$org' AS uuid), 'merge', CASE i % 3 " +
                "WHEN 0 THEN '{\"repo\":\"acme/r1\",\"realRepo\":\"acme/real\"}' " +
                "WHEN 1 THEN '{\"repo\":\"acme/r2\",\"realRepo\":\"\"}' ELSE '{\"repo\":\"\"}' END, " +
                "'Merge ' || i, 'ENFORCE', CASE i % 5 WHEN 0 THEN 'PENDING' WHEN 1 THEN 'REJECTED' " +
                "WHEN 2 THEN 'SHADOW' ELSE 'APPROVED' END, CASE WHEN i % 4 = 0 THEN '[]' ELSE " +
                "'[\"claude\",\"codex\"]' END, 'cli', '[]', '[]', 'd', '{}', CASE i % 5 WHEN 0 THEN NULL " +
                "WHEN 1 THEN '${OLGA.userId}' WHEN 2 THEN '${CAROL.userId}' ELSE 'rev' END, " +
                "CASE WHEN i % 5 = 0 THEN NULL ELSE $at END, $at FROM generate_series(1, 90) AS i",
            "INSERT INTO compiler_decisions (org_id, id, subject_kind, subject_id, decided_by, decided_at) SELECT " +
                "CAST('$org' AS uuid), 'd' || i, CASE WHEN i % 4 = 0 THEN 'rule' ELSE 'workflow' END, 'wf' || i, " +
                "CASE i % 3 WHEN 0 THEN '${MIA.userId}' WHEN 1 THEN 'someone' ELSE '${CAROL.userId}' END, " +
                "CAST($at AS TEXT) FROM generate_series(1, 40) AS i",
        )
    }

    private suspend fun TestServer.pageBySql(before: ActivityCursor?, repo: String?, limit: Int): List<String> {
        val text = ReportsSqlBeforeExposed.activity(cursor = before != null, repo = repo != null)
        val values = mutableMapOf<String, Any?>("org_id" to ORG_A, "limit" to limit)
        before?.let { values["before_at"] = it.at; values["before_id"] = it.id }
        repo?.let { values["repo"] = it }
        return inOrganization(ORG_A) {
            queryNamed(text, values) { row ->
                val cost = row.getString("cost_usd")?.let { (it.toDouble() * 1_000_000.0).roundToLong() }
                listOf(
                    row.getString("item_id"),
                    row.getObject("at", OffsetDateTime::class.java).toInstant().toKotlinInstant(),
                    row.getString("kind"), row.getString("actor"), row.getString("subject"),
                    row.getString("outcome"), row.getString("repo"), cost, row.getString("session_id"),
                    row.getString("gate_id"),
                ).joinToString("|")
            }
        }
    }

    private fun ActivityItem.line(): String = listOf(
        id, at, kind.wire, actor, subject, outcome, repo, costMicros, sessionId, gateId,
    ).joinToString("|")

    private suspend fun TestServer.assertSameFeed(repo: String?, limit: Int) {
        val repository = koin.get<ReportsRepository>()
        var before: ActivityCursor? = null
        var pages = 0
        do {
            val sql = pageBySql(before, repo, limit)
            val exposed = repository.activity(ORG_A, before, repo, limit)
            assertEquals(sql, exposed.map { it.line() }, "page $pages of $repo, $limit a page")
            before = exposed.lastOrNull()?.cursor
            pages++
        } while (exposed.size == limit)
        assertTrue(pages > 3, "$repo: the feed spans pages")
    }

    @Test
    fun theFeedPagesAreTheOnesTheSqlGave() = serverTest(modules, reportsOverrides(FixedClock())) { server ->
        server.seedPeople()
        for (org in listOf(ORG_A, ORG_B)) {
            server.seedIn(org, feedOf(org))
        }
        val first = server.koin.get<ReportsRepository>().activity(ORG_A, null, null, 200)
        assertTrue(first.any { it.actor == "olga" }, "A member who decided is shown by login")
        assertTrue(first.any { it.actor == CAROL.userId.toString() }, "A stranger is shown by id")
        assertTrue(first.any { it.repo == "acme/real" } && first.any { it.repo == "acme/r2" })
        for (limit in listOf(7, 10, 51)) server.assertSameFeed(null, limit)
        server.assertSameFeed("acme/r1", 7)
    }

    // A ledger whose calls tie on every way: the same sessions, launches, members and days, the same costs.
    private fun ledgerOf(org: Uuid): String {
        val epoch = NOW.epochSeconds
        return "INSERT INTO usage_events (id, org_id, member_id, kind, name, session_id, launch_id, input_tokens, " +
            "output_tokens, cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) SELECT " +
            "gen_random_uuid(), CAST('$org' AS uuid), CASE i % 4 WHEN 0 THEN CAST('${OLGA.memberId}' AS uuid) " +
            "WHEN 1 THEN CAST('${MIA.memberId}' AS uuid) WHEN 2 THEN CAST(md5('stranger') AS uuid) END, 'MODEL', " +
            "'claude', CASE i % 5 WHEN 0 THEN NULL WHEN 1 THEN 'lch' || (i % 7) WHEN 2 THEN 'gone' ELSE " +
            "CASE WHEN i % 4 = 0 THEN 'S' ELSE 's' END || CASE WHEN i % 5 = 0 THEN '-' ELSE '_' END || " +
            "(i % 120) END, " +
            "CASE WHEN i % 5 = 1 THEN 'lch' || (i % 7) END, 1, 1, 0, 0, 1000, 5, 'ok', " +
            "to_timestamp($epoch) - (i % 900) * INTERVAL '1 hour' FROM generate_series(1, 6000) AS i"
    }

    @Test
    fun theSpendingLinesAreTheOnesTheSqlGave() = serverTest(modules, reportsOverrides(FixedClock())) { server ->
        server.seedPeople()
        for (org in listOf(ORG_A, ORG_B)) {
            server.seedIn(org, feedOf(org) + ledgerOf(org))
        }
        val windows = listOf(
            startOf(LocalDate(2026, 10, 1)) to NOW,
            NOW - 40.days to NOW,
            NOW - 30.days - 5.hours to NOW - 2.days - 3.hours,
            NOW - 5.hours to NOW,
        )
        for ((from, to) in windows) {
            for (member in listOf(null, OLGA.memberId, MIA.memberId)) {
                for (ranges in listOf(SpendingRanges.of(from, to), SpendingRanges.allHours(from, to))) {
                    val sql = server.spendingBySql(ORG_A, member, ranges)
                    assertTrue(sql.isNotEmpty(), "$from to $to, $member: the window has spending")
                    assertEquals(sql, server.spendingByExposed(ORG_A, member, ranges), "$from to $to, $member")
                }
            }
        }
        val month = server.spendingByExposed(ORG_A, null, SpendingRanges.of(NOW - 40.days, NOW))
        assertTrue(month.any { it.startsWith("agent||") }, "Calls outside any session: $month")
        assertTrue(month.any { it.startsWith("person|${OLGA.memberId}|olga|") }, "A member by login")
        assertTrue(month.any { it.startsWith("person|||") }, "Calls of no member")
    }
}
