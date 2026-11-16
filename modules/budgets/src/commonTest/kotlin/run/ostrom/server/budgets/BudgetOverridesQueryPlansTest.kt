// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.infrastructure.activeRead
import run.ostrom.server.budgets.infrastructure.automaticRead
import run.ostrom.server.budgets.infrastructure.historyRead
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import org.jetbrains.exposed.v1.jdbc.explain
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The override reads under `EXPLAIN ANALYZE`, as the serving role with row-level security, over 20 000 rows of other
 * orgs and members: the one in force (read before each model call of a member with limits) and a history page go
 * through their indexes, never a scan.
 */
class BudgetOverridesQueryPlansTest {
    private val modules = listOf(SecretsModule, UsageModule, BudgetsModule)
    private val overrides = module {
        includes(TestCallers.overrides(TestClock()))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }

    @Test
    fun theOverrideReadsUseTheirIndexes() = serverTest(modules, overrides) { server ->
        server.database.superuserOutsideTransaction(
            "INSERT INTO budgets_overrides (id, org_id, member_id, micros, created_by, reason, created_at, " +
                "expires_at, revoked_at) SELECT gen_random_uuid(), CAST(md5('o' || (i % 2000)) AS uuid), " +
                "CAST(md5('m' || (i % 4000)) AS uuid), i, gen_random_uuid(), 'r', now(), now() + interval '1 hour', " +
                "CASE WHEN i % 3 = 0 THEN now() END FROM generate_series(0, 19999) AS i",
            "VACUUM ANALYZE budgets_overrides",
        )
        val org = TestCallers.orgA
        val member = TestCallers.memberA.memberId
        val database = server.koin.get<JdbcDatabase>()
        val reads = listOf(
            "the override in force" to activeRead(org, member),
            "a history's first page" to historyRead(org, member, null),
            "a history's next page" to historyRead(org, member, Instant.parse("2026-11-15T00:00:00Z") to Uuid.random()),
        )
        for ((name, read) in reads) {
            val plan = database.inOrg(org) {
                explain(analyze = true, options = "BUFFERS") { read }.joinToString("\n") { it.toString() }
            }
            assertFalse("Seq Scan" in plan, "$name scans the table:\n$plan")
        }
    }

    @Test
    fun theAutomaticDailyBudgetIsReadByItsPrimaryKey() = serverTest(modules, overrides) { server ->
        server.database.superuserOutsideTransaction(
            "INSERT INTO budgets_automatic_daily (org_id, micros, computed_at) " +
                "SELECT CAST(md5('o' || i) AS uuid), i, now() FROM generate_series(0, 19999) AS i",
            "VACUUM ANALYZE budgets_automatic_daily",
        )
        val org = TestCallers.orgA
        val plan = server.koin.get<JdbcDatabase>().inOrg(org) {
            explain(analyze = true, options = "BUFFERS") { automaticRead(org).query }
                .joinToString("\n") { it.toString() }
        }
        assertFalse("Seq Scan" in plan, "The read of each call's limits scans the table:\n$plan")
    }
}
