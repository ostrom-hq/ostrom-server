// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAlerts
import dev.ostrom.server.budgets.presentation.MyBudgetResponse
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.writeReadReport
import dev.ostrom.server.usage.UsageModule
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
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
                "$LIMIT_MICROS, now())",
        ).getOrThrow()
        fixtures.execute("VACUUM ANALYZE usage_events").getOrThrow()

        // The member's own limit applies, under the organization's: the route reads both and the month's spending.
        val mine = server.client.get("/api/v1/budgets/me") { bearerAuth("member-a") }.body<MyBudgetResponse>()
        assertEquals(LIMIT_MICROS, mine.limitMicros)
        val measure = server.measureRead("GET /api/v1/budgets/me (organization and member limits)") {
            get("/api/v1/budgets/me") { bearerAuth("member-a") }
        }
        writeReadReport("budgets", listOf(measure))
        measure.assertWithinBudget(setOf("usage_events"))
    }

    private companion object {
        const val ORGS = 10
        const val USAGE_PER_ORG = 100_000
        const val LIMIT_MICROS = 1_000_000_000_000L
    }
}
