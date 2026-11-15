// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.domain.UsageGroupBy
import run.ostrom.server.usage.domain.DailySpendLimits
import run.ostrom.server.usage.domain.HoldWindow
import run.ostrom.server.usage.domain.SpendHold
import run.ostrom.server.usage.domain.SpendLimits
import run.ostrom.server.usage.domain.UsageCursor
import run.ostrom.server.usage.domain.UsageWindow
import run.ostrom.server.usage.infrastructure.ReviewCursor
import run.ostrom.server.usage.infrastructure.accountSpentRead
import run.ostrom.server.usage.infrastructure.historyRead
import run.ostrom.server.usage.infrastructure.holdTotalsRead
import run.ostrom.server.usage.infrastructure.launchSpentRead
import run.ostrom.server.usage.infrastructure.listRead
import run.ostrom.server.usage.infrastructure.pendingRead
import run.ostrom.server.usage.infrastructure.spentRead
import run.ostrom.server.usage.infrastructure.summaryRead
import run.ostrom.server.usage.infrastructure.workflowSpentRead
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The ledger's request-path reads under `EXPLAIN ANALYZE`, on the tenant role with its row-level security policy, over
 * a table where the org is a small part of the rows: each read, as Exposed sends it, goes through an index, never a
 * scan of the table. The plans are written to `build/reports/explain/usage.txt`, uploaded with the CI's reports.
 */
class UsageQueryPlansTest {
    private val clock = TestClock()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }
    private val account = Uuid.parse("000000ac-0000-0000-0000-000000000001")

    /** [count] model events of [org] over the last 60 days, across 30 members, a third of them on [account]. */
    private fun seed(org: Uuid, count: Int): String {
        val member = TestCallers.memberA.memberId
        // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
        return "INSERT INTO usage_events (id, org_id, member_id, kind, name, provider, account_id, session_id, " +
            "input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, " +
            "at, workflow_id) " +
            "SELECT gen_random_uuid(), CAST('$org' AS uuid), CASE WHEN i % 30 = 0 THEN CAST('$member' AS uuid) " +
            "ELSE CAST(md5('m' || (i % 30)) AS uuid) END, 'MODEL', 'claude', 'anthropic', " +
            "CASE WHEN i % 3 = 0 THEN CAST('$account' AS uuid) END, NULL, 10, 10, 0, 0, i % 1000, 5, 'ok', " +
            "to_timestamp(${TestCallers.epoch.epochSeconds}) - (i % 1440) * INTERVAL '1 hour', " +
            "CASE WHEN i % 7 = 0 THEN 'review' WHEN i % 7 = 1 THEN 'w' || (i % 50) END " +
            "FROM generate_series(1, $count) AS i"
    }

    /** [count] holds of [org] over the last 90 days, most of them expired: calls their lost replicas never recorded. */
    private fun holds(org: Uuid, count: Int): String =
        "INSERT INTO usage_holds (call_id, org_id, member_id, workflow_id, micros, expires_at, held_at) " +
            "SELECT gen_random_uuid(), CAST('$org' AS uuid), CAST(md5('m' || (i % 30)) AS uuid), " +
            "CASE WHEN i % 7 = 0 THEN 'review' END, i % 1000, " +
            "to_timestamp(${TestCallers.epoch.epochSeconds}) - (i % 2160) * INTERVAL '1 hour' + INTERVAL '1 hour', " +
            "to_timestamp(${TestCallers.epoch.epochSeconds}) - (i % 2160) * INTERVAL '1 hour' " +
            "FROM generate_series(1, $count) AS i"

    /** [count] corrections of [org] over the last 90 days (the usage review). */
    private fun reconciliations(org: Uuid, count: Int): String =
        "INSERT INTO usage_reconciliations (call_id, org_id, member_id, held_at, previous_micros, micros, source, " +
            "reference, reason, reconciled_by, reconciled_at) " +
            "SELECT gen_random_uuid(), CAST('$org' AS uuid), CAST(md5('m' || (i % 30)) AS uuid), " +
            "to_timestamp(${TestCallers.epoch.epochSeconds}) - (i % 2160) * INTERVAL '1 hour', 10, 5, " +
            "'PROVIDER_RECORD', 'ref', 'why', CAST(md5('a') AS uuid), " +
            "to_timestamp(${TestCallers.epoch.epochSeconds}) - (i % 2000) * INTERVAL '1 hour' " +
            "FROM generate_series(1, $count) AS i"

    /** Fixtures only: the rows of two organizations at once and the statistics, which no request writes. */
    private suspend fun TestServer.fill() {
        val fixtures = database.superuser
        fixtures.execute(seed(TestCallers.orgB, 20_000)).getOrThrow()
        fixtures.execute(seed(TestCallers.orgA, 2_000)).getOrThrow()
        fixtures.execute(holds(TestCallers.orgB, 20_000)).getOrThrow()
        fixtures.execute(holds(TestCallers.orgA, 200)).getOrThrow()
        fixtures.execute(reconciliations(TestCallers.orgB, 10_000)).getOrThrow()
        fixtures.execute(reconciliations(TestCallers.orgA, 100)).getOrThrow()
        fixtures.execute("VACUUM ANALYZE usage_reconciliations").getOrThrow()
        fixtures.execute("VACUUM ANALYZE usage_events").getOrThrow()
        fixtures.execute("VACUUM ANALYZE usage_holds").getOrThrow()
        fixtures.execute("VACUUM ANALYZE usage_spend_days").getOrThrow()
    }

    @Test
    fun theLedgersRequestPathReadsUseTheirIndexes() =
        serverTest(listOf(SecretsModule, UsageModule), overrides) { server ->
            server.fill()
            val org = TestCallers.orgA
            val since = TestCallers.epoch - 30.days
            val member = TestCallers.memberA.memberId
            val hold = SpendHold(org, member, Uuid.random(), micros = 1_000, workflowId = "review")
            val window = HoldWindow(since, TestCallers.epoch, TestCallers.epoch + 5.minutes)
            val today = DailySpendLimits(TestCallers.epoch - 1.hours, orgMicros = 1, memberMicros = 1)
            val reads = linkedMapOf(
                "spend of the org (budgets, gateway)" to spentRead(org, null, since).query,
                "spend of a member (budgets, gateway)" to spentRead(org, member, since).query,
                "spend of a shared account (sharing)" to accountSpentRead(org, account, since).query,
                "newest page of the org" to listRead(org, null, null, 50).query,
                "next page of the org" to listRead(org, null, UsageCursor(since, Uuid.random()), 50).query,
                "next page of a member" to listRead(org, member, UsageCursor(since, Uuid.random()), 50).query,
                "spend of a launch while its session ran (session receipt)" to
                    launchSpentRead(org, "lch_1", TestCallers.epoch - 2.hours, TestCallers.epoch).query,
                "spend and holds of the org and a member, by the month and the day (gateway admission)" to
                    holdTotalsRead(hold.copy(workflowId = null), SpendLimits(1, 1, today), window).query,
                "spend of a workflow (its budgets)" to workflowSpentRead(org, "review", since).query,
                "spend and holds of a workflow, by the month and the day (gateway admission)" to
                    holdTotalsRead(
                        hold,
                        SpendLimits(null, null, DailySpendLimits(today.since, null, null, 1), workflowMicros = 1),
                        window,
                    ).query,
                "spend and holds of a launch over its life (gateway admission)" to
                    holdTotalsRead(
                        hold.copy(launchId = "lch_1"),
                        SpendLimits(null, null, launchMicros = 1),
                        window,
                    ).query,
                "unknown usage awaiting review, newest page (usage review)" to
                    pendingRead(org, null, null, 51).query,
                "unknown usage awaiting review, next page of a member (usage review)" to
                    pendingRead(org, member, ReviewCursor(TestCallers.epoch - 10.days, Uuid.random()), 51).query,
                "corrections, newest page (usage review)" to historyRead(org, null, null, 51).query,
                "corrections, next page of a member (usage review)" to
                    historyRead(org, member, ReviewCursor(TestCallers.epoch - 10.days, Uuid.random()), 51).query,
                "summary of 30 days by model" to
                    summaryRead(UsageWindow(org, null, since), UsageGroupBy.MODEL, 1000).query,
            )
            val database = server.koin.get<JdbcDatabase>()
            val plans = reads.mapValues { (_, read) -> database.inOrg(org) { plan { read } } }
            val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
            SystemFileSystem.createDirectories(Path("build/reports/explain"))
            SystemFileSystem.sink(Path("build/reports/explain/usage.txt")).buffered().use { it.writeString(report) }
            for ((name, plan) in plans) {
                assertFalse("Seq Scan on usage_events" in plan, "$name scans the table:\n$plan")
                assertFalse("Seq Scan on usage_holds" in plan, "$name scans the holds:\n$plan")
                assertFalse("Seq Scan on usage_spend_days" in plan, "$name scans the spend of the days:\n$plan")
                assertFalse("Seq Scan on usage_reconciliations" in plan, "$name scans the corrections:\n$plan")
            }
            // The cursor `(at, id) < (?, ?)` walks the org's index on (org_id, at, id) from where the page starts.
            val next = plans.getValue("next page of the org")
            assertTrue("usage_events_org_at" in next && "Sort" !in next, "The org's next page sorts:\n$next")
        }
}
