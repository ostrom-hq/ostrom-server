// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetLimit
import dev.ostrom.server.budgets.domain.BudgetRules
import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.domain.BudgetWindow
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.budgets.infrastructure.BudgetsQueries
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.UsageModule
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The limits' request-path reads under `EXPLAIN ANALYZE`, on the tenant role with its row-level security policy, over
 * a table where the org is a small part of the rows: the limits of a call (the guard and the gateway's admission, each
 * cache miss) and the workflow budgets of the budgets page go through an index, never a scan of the table. The plans
 * are written to `build/reports/explain/budgets.txt`.
 */
class BudgetsQueryPlansTest {
    private val clock = TestClock()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }

    /** [count] limits of 2 000 other orgs: the org's, a member's, workflows' (no colon: `:x` is a parameter). */
    private fun seed(count: Int): String =
        "INSERT INTO budgets_limits (org_id, member_id, workflow_id, scope, period, action, limit_micros, " +
            "updated_at) " +
            "SELECT CAST(md5('o' || (i % 2000)) AS uuid), " +
            "CASE WHEN i / 2000 = 1 THEN CAST(md5('m' || i) AS uuid) END, " +
            "CASE WHEN i / 2000 > 1 THEN 'w' || (i / 2000) END, " +
            "CASE WHEN i / 2000 = 0 THEN 'ORG' WHEN i / 2000 = 1 THEN 'MEMBER' ELSE 'WORKFLOW' END, " +
            "'MONTH', 'BLOCK', 1000, now() FROM generate_series(0, ${count - 1}) AS i"

    private val modules = listOf(SecretsModule, UsageModule, BudgetsModule)

    @Test
    fun theLimitsReadsUseTheirIndexes() = serverTest(modules, overrides) { server ->
        val fixtures = server.database.superuser
        fixtures.execute(seed(20_000)).getOrThrow()
        val repository = server.koin.get<BudgetsRepository>()
        val member = TestCallers.memberA.memberId
        val now = TestCallers.epoch
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.Org, listOf(BudgetLimit(9_000)), now)
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.Member(member), listOf(BudgetLimit(5_000)), now)
        for (n in 1..20) {
            val limits = listOf(BudgetLimit(3_000), BudgetLimit(300, BudgetWindow.DAY))
            repository.replaceLimits(TestCallers.orgA, BudgetTarget.Workflow("w$n"), limits, now)
        }
        fixtures.execute("VACUUM ANALYZE budgets_limits").getOrThrow()
        val driver = server.driver
        val plans = linkedMapOf(
            "limits of a call that plays a workflow (guard, gateway)" to driver.explain(BudgetsQueries.LIMITS) {
                bind("member_id", member).bind("workflow_id", "w7")
            },
            "limits of a call that plays none" to driver.explain(BudgetsQueries.LIMITS) {
                bind("member_id", member).bindNull("workflow_id", String::class)
            },
            "workflow budgets of the budgets page" to driver.explain(BudgetsQueries.WORKFLOW_LIMITS) {
                bind("limit", BudgetRules.MAX_WORKFLOW_BUDGETS)
            },
        )
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/budgets.txt")).buffered().use { it.writeString(report) }
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on budgets_limits" in plan, "$name scans the table:\n$plan")
        }
        val call = repository.limits(TestCallers.orgA, member, "w7")
        assertEquals(listOf(9_000L, 5_000L), listOf(call.org.single().micros, call.member.single().micros))
        assertEquals(listOf(300L, 3_000L), call.workflow.map { it.micros }.sorted(), "Its workflow's only")
        assertEquals(40, repository.settings(TestCallers.orgA, 0).workflows.size, "Every window of every workflow")
    }

    /** The plan of [sql] in org A, as the tenant role reads it; `:org_id` is bound, [bind] adds the rest. */
    private suspend fun Driver.explain(sql: String, bind: Statement.() -> Statement): String {
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", TestCallers.orgA).bind()
        return fetchInOrg(TestCallers.orgA, statement).getOrThrow().joinToString("\n") {
            it.get("QUERY PLAN").asString()
        }
    }
}
