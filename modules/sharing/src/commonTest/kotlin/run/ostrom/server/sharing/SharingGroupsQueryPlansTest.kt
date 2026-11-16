// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.infrastructure.SharingGroups
import run.ostrom.server.sharing.infrastructure.accountGroupLend
import run.ostrom.server.sharing.infrastructure.accountGroupsDelete
import run.ostrom.server.sharing.infrastructure.accountsLentToMember
import run.ostrom.server.sharing.infrastructure.groupAccountsOf
import run.ostrom.server.sharing.infrastructure.groupAccountsOfOrg
import run.ostrom.server.sharing.infrastructure.groupMembersOf
import run.ostrom.server.sharing.infrastructure.groupMembersOfOrg
import run.ostrom.server.sharing.infrastructure.groupNameTaken
import run.ostrom.server.sharing.infrastructure.groupOf
import run.ostrom.server.sharing.infrastructure.groupsCount
import run.ostrom.server.sharing.infrastructure.groupsOfOrg
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
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

    private fun JdbcTransaction.groups(org: Uuid, count: Int) = exec(
        "INSERT INTO sharing_groups (id, org_id, name, created_at) SELECT gen_random_uuid(), " +
            "?, 'group-' || i, now() FROM generate_series(1, ?) AS i",
        listOf(UuidColumnType() to org, IntegerColumnType() to count),
    )

    private fun JdbcTransaction.account(org: Uuid, id: Uuid = Uuid.random()) = exec(
        "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, owner_member_id, " +
            "priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, allowed_members, " +
            "cooldown_until, envelope, created_at) VALUES (?, ?, 'CLAUDE', 'seed', NULL, 'seed-$org', NULL, 0, " +
            "'READY', 8, 0.95, NULL, NULL, NULL, 'sealed', now())",
        listOf(UuidColumnType() to id, UuidColumnType() to org),
    )

    @Test
    fun theReadsOfTheGroupsScanNoTable() = serverTest(
        listOf(SecretsModule, SharingModule),
        module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } },
    ) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        server.database.superuserJdbc.query {
            groups(orgB, 20_000)
            groups(orgA, 40)
            exec(
                "INSERT INTO sharing_groups (id, org_id, name, created_at) VALUES (?, ?, 'Backend', now())",
                listOf(UuidColumnType() to groupA, UuidColumnType() to orgA),
            )
            exec(
                "INSERT INTO sharing_group_members (org_id, group_id, member_id, paused_until) " +
                    "SELECT g.org_id, g.id, gen_random_uuid(), NULL FROM sharing_groups g, generate_series(1, 5) AS i",
            )
            account(orgA, accountA)
            account(orgB)
            exec(
                "INSERT INTO sharing_group_accounts (org_id, group_id, account_id) " +
                    "SELECT g.org_id, g.id, a.id FROM sharing_groups g JOIN sharing_accounts a ON a.org_id = g.org_id",
            )
            exec(
                "INSERT INTO sharing_group_members (org_id, group_id, member_id, paused_until) " +
                    "VALUES (?, ?, ?, NULL)",
                listOf(UuidColumnType() to orgA, UuidColumnType() to groupA, UuidColumnType() to memberA),
            )
        }
        server.database.superuserOutsideTransaction(
            "VACUUM ANALYZE sharing_groups",
            "VACUUM ANALYZE sharing_group_members",
            "VACUUM ANALYZE sharing_group_accounts",
        )

        val n = SharingGroups.id.count()
        val database = server.koin.get<JdbcDatabase>()
        mapOf<String, StatementBuilder.() -> Statement<*>>(
            "the list" to { groupsOfOrg(orgA) },
            "the members of the list" to { groupMembersOfOrg(orgA) },
            "the accounts of the list" to { groupAccountsOfOrg(orgA) },
            "one group" to { groupOf(orgA, groupA) },
            "its members" to { groupMembersOf(orgA, groupA) },
            "its accounts" to { groupAccountsOf(orgA, groupA) },
            "the count" to { groupsCount(orgA, n) },
            "a name taken" to { groupNameTaken(orgA, groupA, "backend") },
            "the accounts lent to a member" to { accountsLentToMember(orgA, memberA, Clock.System.now()) },
            "an account lent to a group" to { accountGroupLend(orgA, accountA, groupA) },
            "the groups of an account dropped" to { accountGroupsDelete(orgA, accountA) },
        ).forEach { (what, statement) ->
            val plan = database.inOrg(orgA) { plan(statement).also { rollback() } }
            assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
        }
    }
}
