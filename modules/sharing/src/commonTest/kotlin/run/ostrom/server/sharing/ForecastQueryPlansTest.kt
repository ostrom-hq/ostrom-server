// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.infrastructure.accountOfOrg
import run.ostrom.server.sharing.infrastructure.quotaReading
import run.ostrom.server.sharing.infrastructure.recentQuotaHistory
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * The quota history's queries under `EXPLAIN (ANALYZE, BUFFERS)`, as the tenant role under row-level security, over a
 * history where the account's readings are a small part (hundreds of accounts of another organization, two hours of
 * readings each): the forecast's read, the account check and the reading's write each read an index, never a scan.
 */
class ForecastQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a7")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b7")
    private val accountA = Uuid.parse("00000000-0000-4000-8000-0000000000d7")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun accounts(org: Uuid, count: Int): String =
        "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, owner_member_id, " +
            "priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, allowed_members, " +
            "cooldown_until, envelope, created_at) SELECT gen_random_uuid(), CAST('$org' AS uuid), 'CLAUDE', " +
            "'seed', NULL, 'seed-' || i, NULL, 0, 'READY', 0, 0.95, NULL, NULL, NULL, 'sealed', now() " +
            "FROM generate_series(1, $count) AS i"

    @Test
    fun theHistorysQueriesReadAnIndex() = serverTest(
        listOf(SecretsModule, SharingModule),
        module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } },
    ) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(accounts(orgB, 500)).getOrThrow()
        fixtures.execute(
            "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, owner_member_id, " +
                "priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, allowed_members, " +
                "cooldown_until, envelope, created_at) VALUES (CAST('$accountA' AS uuid), CAST('$orgA' AS uuid), " +
                "'CLAUDE', 'seed', NULL, 'seed-a', NULL, 0, 'READY', 0, 0.95, NULL, NULL, NULL, 'sealed', now())",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO sharing_quota_history (org_id, account_id, name, utilization, resets_at, observed_at, " +
                "sample_minute) SELECT a.org_id, a.id, w.name, 0.5, now() + INTERVAL '1 hour', " +
                "now() - m * INTERVAL '1 minute', CAST(floor(extract(epoch FROM now()) / 60) AS BIGINT) - m " +
                "FROM sharing_accounts a, (VALUES ('five_hour'), ('seven_day'), ('model' || chr(58) || 'opus')) " +
                "AS w(name), generate_series(0, 119) AS m",
        ).getOrThrow()
        listOf("sharing_accounts", "sharing_quota_history").forEach {
            fixtures.execute("VACUUM ANALYZE $it").getOrThrow()
        }

        val now = Clock.System.now()
        val windows = listOf("five_hour", "seven_day", "model:opus")
        val database = server.koin.get<JdbcDatabase>()
        val plans = database.inOrg(orgA) {
            mapOf(
                "the forecast's read" to plan {
                    recentQuotaHistory(orgA, accountA, windows, (now - 1.hours)..now, 8_193)
                },
                "the account checked" to plan { accountOfOrg(orgA, accountA) },
                "the reading written" to plan {
                    quotaReading(orgA, accountA, QuotaWindow("five_hour", .6, now + 1.hours, now))
                },
            )
        }
        plans.forEach { (what, plan) ->
            assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
        }
    }

}
