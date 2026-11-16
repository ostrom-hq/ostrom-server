// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.domain.PoolBudget
import run.ostrom.server.sharing.domain.PoolPolicy
import run.ostrom.server.sharing.infrastructure.homeOf
import run.ostrom.server.sharing.infrastructure.memberPoolClear
import run.ostrom.server.sharing.infrastructure.memberPoolOf
import run.ostrom.server.sharing.infrastructure.memberPoolPut
import run.ostrom.server.sharing.infrastructure.memberPoolsOf
import run.ostrom.server.sharing.infrastructure.otherReserves
import run.ostrom.server.sharing.infrastructure.poolBudgetPut
import run.ostrom.server.sharing.infrastructure.poolBudgetsOf
import run.ostrom.server.sharing.infrastructure.reserveAccounts
import run.ostrom.server.sharing.infrastructure.reservesOf
import run.ostrom.server.sharing.infrastructure.sharedPool
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The reads and writes of the members' home pools under `EXPLAIN ANALYZE`, as the tenant role under row-level
 * security, over a table where the organization's rows are a small part (tens of thousands of members of other
 * organizations): the lease's read of one member's pool goes through the key, and none scans the table.
 */
class MemberPoolsQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a6")
    private val memberA = Uuid.parse("00000000-0000-4000-8000-0000000000e6")

    /** Fixtures only: pool budgets and accounts of many organizations, and org A's sixteen reserves. */
    private suspend fun TestServer.reserves() {
        database.superuserJdbc.query {
            exec(
                "INSERT INTO sharing_pool_budgets (org_id, pool_id, daily_micros, shared_overflow) SELECT " +
                    "gen_random_uuid(), 'pool-' || (i % 7), 1000, i % 2 = 0 FROM generate_series(1, ?) AS i",
                listOf(IntegerColumnType() to 30_000),
            )
            exec(
                "INSERT INTO sharing_pool_budgets (org_id, pool_id, daily_micros, shared_overflow) SELECT ?, " +
                    "'reserve-' || i, 1000, true FROM generate_series(1, 16) AS i",
                listOf(UuidColumnType() to orgA),
            )
            exec(
                "INSERT INTO sharing_accounts (id, org_id, provider, label, provider_account_id, priority, status, " +
                    "max_concurrent, utilization_ceiling, envelope, created_at, pool_id) SELECT gen_random_uuid(), " +
                    "gen_random_uuid(), 'claude', 'a', 'acct-' || i, 0, 'READY', 0, 1, 'x', now(), " +
                    "'pool-' || (i % 7) FROM generate_series(1, ?) AS i",
                listOf(IntegerColumnType() to 20_000),
            )
        }
    }

    @Test
    fun theMemberPoolStatementsScanNoTable() = serverTest(
        listOf(SecretsModule, SharingModule),
        module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } },
    ) { server ->
        // Fixtures only: rows of many organizations at once and the statistics, which no request writes.
        server.database.superuserJdbc.query {
            exec(
                "INSERT INTO sharing_member_pools (org_id, member_id, pool_id) SELECT gen_random_uuid(), " +
                    "gen_random_uuid(), 'pool-' || (i % 7) FROM generate_series(1, ?) AS i",
                listOf(IntegerColumnType() to 30_000),
            )
            exec(
                "INSERT INTO sharing_member_pools (org_id, member_id, pool_id) SELECT ?, gen_random_uuid(), 'eu' " +
                    "FROM generate_series(1, 40)",
                listOf(UuidColumnType() to orgA),
            )
            exec(
                "INSERT INTO sharing_member_pools (org_id, member_id, pool_id) VALUES (?, ?, 'eu')",
                listOf(UuidColumnType() to orgA, UuidColumnType() to memberA),
            )
        }
        server.reserves()
        server.database.superuserOutsideTransaction(
            "VACUUM ANALYZE sharing_member_pools",
            "VACUUM ANALYZE sharing_pool_budgets",
            "VACUUM ANALYZE sharing_accounts",
        )

        val database = server.koin.get<JdbcDatabase>()
        val others = List(20) { Uuid.random() } + memberA
        val plans = mapOf<String, StatementBuilder.() -> Statement<*>>(
            "a member's pool" to { memberPoolOf(orgA, memberA) },
            "the pools of the members advised" to { memberPoolsOf(orgA, others) },
            "a member put in a pool" to { memberPoolPut(orgA, memberA, PoolPolicy("us", true)) },
            "a member taken out of their pool" to { memberPoolClear(orgA, memberA) },
            "the budgets of the organization" to { poolBudgetsOf(orgA) },
            "whether a pool is shared (a member's policy)" to { sharedPool(orgA, "eu") },
            "whether a pool is someone's home (a budget)" to { homeOf(orgA, "eu") },
            "the other shared pools (a budget)" to { otherReserves(orgA, "eu") },
            "the reserves no member calls home (a lease)" to { reservesOf(orgA) },
            "the accounts of the reserves (a lease)" to { reserveAccounts(orgA, List(16) { "reserve-$it" }) },
            "a pool budget saved" to { poolBudgetPut(orgA, PoolBudget("reserve-1", 1_000, true)) },
        ).mapValues { (_, statement) -> database.inOrg(orgA) { plan(statement).also { rollback() } } }
        plans.forEach { (what, plan) -> assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan") }
        assertTrue("sharing_member_pools_pkey" in plans.getValue("a member's pool"), plans.getValue("a member's pool"))
        // The plans of the request-path reads, for the pull request's evidence.
        println("EXPLAIN ANALYZE of a member's pool:\n${plans.getValue("a member's pool")}")
        for (read in listOf("the reserves no member calls home (a lease)", "the accounts of the reserves (a lease)")) {
            println("EXPLAIN ANALYZE of $read:\n${plans.getValue(read)}")
        }
    }
}
