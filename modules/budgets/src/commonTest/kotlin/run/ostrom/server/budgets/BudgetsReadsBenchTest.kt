// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.BudgetAlerts
import run.ostrom.server.budgets.presentation.MyBudgetResponse
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.assertWithinBudget
import run.ostrom.server.testing.benchTest
import run.ostrom.server.testing.measureRead
import run.ostrom.server.testing.writeReadReport
import run.ostrom.server.usage.UsageModule
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/budgets/me` on a ledger of 1 000 000 model calls of 10 organizations over 60 days, with an organization
 * limit and a member limit: the guard sums the month of the organization and of the member. It keeps the read budget
 * (ReadBench.kt); its queries, plans and p95 are written to `build/reports/perf/budgets.txt`.
 */
class BudgetsReadsBenchTest {
    private val overrides: Module = module {
        includes(TestCallers.overrides(TestClock()))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<BudgetAlerts> { RecordingAlerts() }
    }
    private val orgs = listOf(TestCallers.orgA, TestCallers.orgB) + List(ORGS - 2) { Uuid.random() }

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter. A tenth of each
    // organization's calls are the member's.
    private fun usageOf(org: Uuid): String {
        val epoch = TestCallers.epoch.epochSeconds
        return "INSERT INTO usage_events (id, org_id, member_id, kind, name, session_id, input_tokens, " +
            "output_tokens, cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) SELECT " +
            "gen_random_uuid(), CAST('$org' AS uuid), CASE WHEN i % 10 = 0 THEN " +
            "CAST('${TestCallers.memberA.memberId}' AS uuid) ELSE CAST(md5('m' || (i % 28)) AS uuid) END, 'MODEL', " +
            "'claude', 's' || (i % 5000), 10, 10, 0, 0, 1, 5, 'ok', " +
            "to_timestamp($epoch) - (i % 1440) * INTERVAL '1 hour' - (i % 60) * INTERVAL '1 minute' " +
            "FROM generate_series(1, $USAGE_PER_ORG) AS i"
    }

    @Test
    fun myBudgetKeepsItsBudget() = benchTest(listOf(SecretsModule, UsageModule, BudgetsModule), overrides) { server ->
        val fixtures = server.database.superuser
        orgs.forEach { fixtures.execute(usageOf(it)).getOrThrow() }
        fixtures.execute(
            "INSERT INTO budgets_limits (org_id, member_id, scope, limit_micros, updated_at) VALUES " +
                "(CAST('${TestCallers.orgA}' AS uuid), NULL, 'ORG', $LIMIT_MICROS, now()), " +
                "(CAST('${TestCallers.orgA}' AS uuid), CAST('${TestCallers.memberA.memberId}' AS uuid), 'MEMBER', " +
                "$MEMBER_LIMIT_MICROS, now())",
        ).getOrThrow()
        fixtures.execute("VACUUM ANALYZE usage_events").getOrThrow()

        // The member's own limit, not the organization's: the route reads both and the month's spending.
        val response = server.client.get("/api/v1/budgets/me") { bearerAuth("member-a") }
        assertTrue(response.status.isSuccess(), response.status.toString())
        assertEquals(MEMBER_LIMIT_MICROS, response.body<MyBudgetResponse>().limitMicros)
        val measure = server.measureRead("GET /api/v1/budgets/me (organization and member limits)") {
            get("/api/v1/budgets/me") { bearerAuth("member-a") }
        }
        writeReadReport("budgets", listOf(measure))
        measure.assertWithinBudget(setOf("usage_events"), MAX_QUERIES)
    }

    private companion object {
        const val ORGS = 10

        // The queries of the busiest read today (the organization's and the member's limits, Build 41717982170):
        // one more is an N+1, on any runner.
        const val MAX_QUERIES = 2
        const val USAGE_PER_ORG = 100_000
        const val LIMIT_MICROS = 1_000_000_000_000L
        const val MEMBER_LIMIT_MICROS = LIMIT_MICROS / 2
    }
}
