// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.infrastructure.memberPoolClear
import run.ostrom.server.sharing.infrastructure.memberPoolOf
import run.ostrom.server.sharing.infrastructure.memberPoolPut
import run.ostrom.server.sharing.infrastructure.memberPoolsOf
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
        server.database.superuserOutsideTransaction("VACUUM ANALYZE sharing_member_pools")

        val database = server.koin.get<JdbcDatabase>()
        val others = List(20) { Uuid.random() } + memberA
        val plans = mapOf<String, StatementBuilder.() -> Statement<*>>(
            "a member's pool" to { memberPoolOf(orgA, memberA) },
            "the pools of the members advised" to { memberPoolsOf(orgA, others) },
            "a member put in a pool" to { memberPoolPut(orgA, memberA, "us") },
            "a member taken out of their pool" to { memberPoolClear(orgA, memberA) },
        ).mapValues { (_, statement) -> database.inOrg(orgA) { plan(statement).also { rollback() } } }
        plans.forEach { (what, plan) -> assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan") }
        assertTrue("sharing_member_pools_pkey" in plans.getValue("a member's pool"), plans.getValue("a member's pool"))
        // The plan of the request-path read, for the pull request's evidence.
        println("EXPLAIN ANALYZE of a member's pool:\n${plans.getValue("a member's pool")}")
    }
}
