// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage

import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.domain.UsageGroupBy
import dev.ostrom.server.usage.infrastructure.UsageQueries
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * The ledger's request-path reads under `EXPLAIN ANALYZE`, on the tenant role with its row-level security policy, over
 * a table where the org is a small part of the rows: each read goes through an index, never a scan of the table. The
 * plans are written to `build/reports/explain/usage.txt`, uploaded with the CI's reports.
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
            "at) SELECT gen_random_uuid(), CAST('$org' AS uuid), CASE WHEN i % 30 = 0 THEN CAST('$member' AS uuid) " +
            "ELSE CAST(md5('m' || (i % 30)) AS uuid) END, 'MODEL', 'claude', 'anthropic', " +
            "CASE WHEN i % 3 = 0 THEN CAST('$account' AS uuid) END, NULL, 10, 10, 0, 0, i % 1000, 5, 'ok', " +
            "to_timestamp(${TestCallers.epoch.epochSeconds}) - (i % 1440) * INTERVAL '1 hour' " +
            "FROM generate_series(1, $count) AS i"
    }

    @Test
    fun theLedgersRequestPathReadsUseTheirIndexes() =
        serverTest(listOf(SecretsModule, UsageModule), overrides) { server ->
            // Fixtures only: the rows of two organizations at once and the statistics, which no request writes.
            val fixtures = server.database.superuser
            fixtures.execute(seed(TestCallers.orgB, 20_000)).getOrThrow()
            fixtures.execute(seed(TestCallers.orgA, 2_000)).getOrThrow()
            fixtures.execute("VACUUM ANALYZE usage_events").getOrThrow()
            val driver = server.driver
            val since = TestCallers.epoch - 30.days
            val member = TestCallers.memberA.memberId
            val plans = linkedMapOf(
                "spend of the org (budgets, gateway)" to driver.explain(UsageQueries.spent(member = false)) {
                    bind("since", since)
                },
                "spend of a member (budgets, gateway)" to driver.explain(UsageQueries.spent(member = true)) {
                    bind("since", since).bind("member_id", member)
                },
                "spend of a shared account (sharing)" to driver.explain(UsageQueries.ACCOUNT_SPENT) {
                    bind("since", since).bind("account_id", account)
                },
                "newest page of the org" to driver.explain(UsageQueries.list(false, UsageQueries.Cursor.NONE)) {
                    bind("limit", 50)
                },
                "next page of a member" to driver.explain(UsageQueries.list(true, UsageQueries.Cursor.AT_AND_ID)) {
                    bind("limit", 50).bind("member_id", member)
                        .bind("before_at", since).bind("before_id", Uuid.random())
                },
                "spend of a launch while its session ran (session receipt)" to
                    driver.explain(UsageQueries.LAUNCH_SPENT) {
                        bind(
                            "launch_id",
                            "lch_1",
                        ).bind("from", TestCallers.epoch - 2.hours).bind("to", TestCallers.epoch)
                    },
                "summary of 30 days by model" to
                    driver.explain(UsageQueries.summary(UsageGroupBy.MODEL, false, false)) {
                        bind("since", since).bind("limit", 1000)
                    },
            )
            val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
            SystemFileSystem.createDirectories(Path("build/reports/explain"))
            SystemFileSystem.sink(Path("build/reports/explain/usage.txt")).buffered().use { it.writeString(report) }
            for ((name, plan) in plans) {
                assertFalse("Seq Scan on usage_events" in plan, "$name scans the table:\n$plan")
            }
        }

    /** The plan of [sql] in org A, as the tenant role reads it; `:org_id` is bound, [bind] adds the rest. */
    private suspend fun Driver.explain(sql: String, bind: Statement.() -> Statement): String {
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", TestCallers.orgA).bind()
        return fetchInOrg(TestCallers.orgA, statement).getOrThrow().joinToString("\n") {
            it.get("QUERY PLAN").asString()
        }
    }
}
