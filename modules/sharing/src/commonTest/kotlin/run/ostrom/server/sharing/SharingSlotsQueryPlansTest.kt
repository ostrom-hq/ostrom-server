// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.inTenant
import run.ostrom.server.sharing.infrastructure.AFFINITY_PURGE_SQL
import run.ostrom.server.sharing.infrastructure.AFFINITY_REMEMBER_SQL
import run.ostrom.server.sharing.infrastructure.SLOT_ACTIVE_OF_SQL
import run.ostrom.server.sharing.infrastructure.SLOT_ACTIVE_SQL
import run.ostrom.server.sharing.infrastructure.SLOT_INSERT_SQL
import run.ostrom.server.sharing.infrastructure.SLOT_LOCK_SQL
import run.ostrom.server.sharing.infrastructure.SLOT_PURGE_SQL
import run.ostrom.server.sharing.infrastructure.SLOT_RELEASE_SQL
import run.ostrom.server.sharing.infrastructure.SLOT_REMEMBERED_SQL
import run.ostrom.server.sharing.infrastructure.SLOT_RENEW_SQL
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * The queries every lease of a shared account sends (its slot and its session's account) under `EXPLAIN ANALYZE`, as
 * the tenant role under row-level security, over tables where the organization's rows are a small part (thousands of
 * accounts, slots and sessions of another organization): none scans a table.
 */
class SharingSlotsQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a6")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b6")
    private val accountA = Uuid.parse("00000000-0000-4000-8000-0000000000d6")
    private val memberA = Uuid.parse("00000000-0000-4000-8000-0000000000e6")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun accounts(org: Uuid, count: Int): String =
        "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, owner_member_id, " +
            "priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, allowed_members, " +
            "cooldown_until, envelope, created_at) SELECT gen_random_uuid(), CAST('$org' AS uuid), 'CLAUDE', " +
            "'seed', NULL, 'seed-' || i, NULL, 0, 'READY', 8, 0.95, NULL, NULL, NULL, 'sealed', now() " +
            "FROM generate_series(1, $count) AS i"

    @Test
    fun theQueriesOfALeaseScanNoTable() = serverTest(
        listOf(SecretsModule, SharingModule),
        module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } },
    ) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(accounts(orgB, 5_000)).getOrThrow()
        fixtures.execute(
            "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, owner_member_id, " +
                "priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, allowed_members, " +
                "cooldown_until, envelope, created_at) VALUES (CAST('$accountA' AS uuid), CAST('$orgA' AS uuid), " +
                "'CLAUDE', 'seed', NULL, 'seed-a', NULL, 0, 'READY', 8, 0.95, NULL, NULL, NULL, 'sealed', now())",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO sharing_slots (id, org_id, account_id, expires_at) " +
                "SELECT gen_random_uuid(), a.org_id, a.id, now() + INTERVAL '10 minutes' " +
                "FROM sharing_accounts a, generate_series(1, 4) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO sharing_affinities (org_id, member_id, session_hash, account_id, expires_at) " +
                "SELECT a.org_id, gen_random_uuid(), md5(a.id || '-' || i), a.id, now() + INTERVAL '1 hour' " +
                "FROM sharing_accounts a, generate_series(1, 4) AS i",
        ).getOrThrow()
        listOf("sharing_accounts", "sharing_slots", "sharing_affinities").forEach {
            fixtures.execute("VACUUM ANALYZE $it").getOrThrow()
        }

        mapOf(
            "the candidates locked" to SLOT_LOCK_SQL,
            "their slots" to SLOT_ACTIVE_OF_SQL,
            "the session's account" to SLOT_REMEMBERED_SQL,
            "the expired slots dropped" to SLOT_PURGE_SQL,
            "the slot taken" to SLOT_INSERT_SQL,
            "the expired sessions dropped" to AFFINITY_PURGE_SQL,
            "the session remembered" to AFFINITY_REMEMBER_SQL,
            "the slot renewed" to SLOT_RENEW_SQL,
            "the slot given back" to SLOT_RELEASE_SQL,
            "the org's slots" to SLOT_ACTIVE_SQL,
        ).forEach { (what, sql) ->
            val plan = plan(server.driver, sql)
            assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
        }
    }

    private suspend fun plan(driver: Driver, sql: String): String {
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", orgA)
            .let { if (":ids" in sql) it.bind("ids", accountA.toString()) else it }
            .let { if (":member_id" in sql) it.bind("member_id", memberA) else it }
            .let { if (":session" in sql) it.bind("session", "session-1") else it }
            .let { if (":account_id" in sql) it.bind("account_id", accountA) else it }
            .let { if (":id" in sql.replace(":ids", "")) it.bind("id", Uuid.random()) else it }
        return driver.inTenant(orgA) {
            fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
    }
}
