// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing

import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.infrastructure.inTenant
import dev.ostrom.server.sharing.infrastructure.ACCOUNTS_LENT_TO_MEMBER_SQL
import dev.ostrom.server.sharing.infrastructure.ACCOUNT_GROUPS_DELETE_SQL
import dev.ostrom.server.sharing.infrastructure.ACCOUNT_GROUP_LEND_SQL
import dev.ostrom.server.sharing.infrastructure.GROUPS_COUNT_SQL
import dev.ostrom.server.sharing.infrastructure.GROUPS_OF_ORG_SQL
import dev.ostrom.server.sharing.infrastructure.GROUP_ACCOUNTS_OF_ORG_SQL
import dev.ostrom.server.sharing.infrastructure.GROUP_ACCOUNTS_SQL
import dev.ostrom.server.sharing.infrastructure.GROUP_MEMBERS_OF_ORG_SQL
import dev.ostrom.server.sharing.infrastructure.GROUP_MEMBERS_SQL
import dev.ostrom.server.sharing.infrastructure.GROUP_NAME_TAKEN_SQL
import dev.ostrom.server.sharing.infrastructure.GROUP_SQL
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The reads of `/api/v1/groups` under `EXPLAIN ANALYZE`, as the tenant role under row-level security, over group
 * tables where the organization's rows are a small part (thousands of groups, members and lent accounts of another
 * organization): none scans a table.
 */
class SharingGroupsQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a5")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b5")
    private val groupA = Uuid.parse("00000000-0000-4000-8000-0000000000c5")
    private val accountA = Uuid.parse("00000000-0000-4000-8000-0000000000d5")
    private val memberA = Uuid.parse("00000000-0000-4000-8000-0000000000e5")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun groups(org: Uuid, count: Int): String =
        "INSERT INTO sharing_groups (id, org_id, name, created_at) SELECT gen_random_uuid(), " +
            "CAST('$org' AS uuid), 'group-' || i, now() FROM generate_series(1, $count) AS i"

    private fun account(org: Uuid, id: String = "gen_random_uuid()"): String =
        "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, owner_member_id, " +
            "priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, allowed_members, " +
            "cooldown_until, envelope, created_at) VALUES ($id, CAST('$org' AS uuid), 'CLAUDE', " +
            "'seed', NULL, 'seed-$org', NULL, 0, 'READY', 8, 0.95, NULL, NULL, NULL, 'sealed', now())"

    @Test
    fun theReadsOfTheGroupsScanNoTable() = serverTest(
        listOf(SecretsModule, SharingModule),
        module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } },
    ) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(groups(orgB, 20_000)).getOrThrow()
        fixtures.execute(groups(orgA, 40)).getOrThrow()
        fixtures.execute(
            "INSERT INTO sharing_groups (id, org_id, name, created_at) " +
                "VALUES (CAST('$groupA' AS uuid), CAST('$orgA' AS uuid), 'Backend', now())",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO sharing_group_members (org_id, group_id, member_id, paused_until) " +
                "SELECT g.org_id, g.id, gen_random_uuid(), NULL FROM sharing_groups g, generate_series(1, 5) AS i",
        ).getOrThrow()
        fixtures.execute(account(orgA, "CAST('$accountA' AS uuid)")).getOrThrow()
        fixtures.execute(account(orgB)).getOrThrow()
        fixtures.execute(
            "INSERT INTO sharing_group_accounts (org_id, group_id, account_id) " +
                "SELECT g.org_id, g.id, a.id FROM sharing_groups g JOIN sharing_accounts a ON a.org_id = g.org_id",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO sharing_group_members (org_id, group_id, member_id, paused_until) " +
                "VALUES (CAST('$orgA' AS uuid), CAST('$groupA' AS uuid), CAST('$memberA' AS uuid), NULL)",
        ).getOrThrow()
        listOf("sharing_groups", "sharing_group_members", "sharing_group_accounts").forEach {
            fixtures.execute("VACUUM ANALYZE $it").getOrThrow()
        }

        mapOf(
            "the list" to GROUPS_OF_ORG_SQL,
            "the members of the list" to GROUP_MEMBERS_OF_ORG_SQL,
            "the accounts of the list" to GROUP_ACCOUNTS_OF_ORG_SQL,
            "one group" to GROUP_SQL,
            "its members" to GROUP_MEMBERS_SQL,
            "its accounts" to GROUP_ACCOUNTS_SQL,
            "the count" to GROUPS_COUNT_SQL,
            "a name taken" to GROUP_NAME_TAKEN_SQL,
            "the accounts lent to a member" to ACCOUNTS_LENT_TO_MEMBER_SQL,
            "an account lent to a group" to ACCOUNT_GROUP_LEND_SQL,
            "the groups of an account dropped" to ACCOUNT_GROUPS_DELETE_SQL,
        ).forEach { (what, sql) ->
            val plan = plan(server.driver, sql)
            assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
        }
    }

    private suspend fun plan(driver: Driver, sql: String): String {
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", orgA)
            .let { if (":id" in sql) it.bind("id", groupA) else it }
            .let { if (":name" in sql) it.bind("name", "backend") else it }
            .let { if (":account" in sql) it.bind("account", accountA) else it }
            .let { if (":group_id" in sql) it.bind("group_id", groupA) else it }
            .let { if (":member" in sql) it.bind("member", memberA).bind("now", Clock.System.now()) else it }
        return driver.inTenant(orgA) {
            fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
    }
}
