// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.domain.AccountSlot
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.infrastructure.affinitiesActive
import run.ostrom.server.sharing.infrastructure.affinityKnown
import run.ostrom.server.sharing.infrastructure.affinityPurge
import run.ostrom.server.sharing.infrastructure.affinityRemember
import run.ostrom.server.sharing.infrastructure.memberAffinities
import run.ostrom.server.sharing.infrastructure.orgAffinities
import run.ostrom.server.sharing.infrastructure.sessionHash
import run.ostrom.server.sharing.infrastructure.slotInsert
import run.ostrom.server.sharing.infrastructure.slotLock
import run.ostrom.server.sharing.infrastructure.slotPurge
import run.ostrom.server.sharing.infrastructure.slotRelease
import run.ostrom.server.sharing.infrastructure.slotRemembered
import run.ostrom.server.sharing.infrastructure.slotRenew
import run.ostrom.server.sharing.infrastructure.slotsActive
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
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

    private fun JdbcTransaction.accounts(org: Uuid, count: Int) = exec(
        "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, owner_member_id, " +
            "priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, allowed_members, " +
            "cooldown_until, envelope, created_at) SELECT gen_random_uuid(), ?, 'CLAUDE', " +
            "'seed', NULL, 'seed-' || i, NULL, 0, 'READY', 8, 0.95, NULL, NULL, NULL, 'sealed', now() " +
            "FROM generate_series(1, ?) AS i",
        listOf(UuidColumnType() to org, IntegerColumnType() to count),
    )

    @Test
    fun theQueriesOfALeaseScanNoTable() = serverTest(
        listOf(SecretsModule, SharingModule),
        module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } },
    ) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        server.database.superuserJdbc.query {
            accounts(orgB, 5_000)
            exec(
                "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, " +
                    "owner_member_id, priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, " +
                    "allowed_members, cooldown_until, envelope, created_at) VALUES (?, ?, 'CLAUDE', 'seed', NULL, " +
                    "'seed-a', NULL, 0, 'READY', 8, 0.95, NULL, NULL, NULL, 'sealed', now())",
                listOf(UuidColumnType() to accountA, UuidColumnType() to orgA),
            )
            exec(
                "INSERT INTO sharing_slots (id, org_id, account_id, expires_at) " +
                    "SELECT gen_random_uuid(), a.org_id, a.id, now() + INTERVAL '10 minutes' " +
                    "FROM sharing_accounts a, generate_series(1, 4) AS i",
            )
            exec(
                "INSERT INTO sharing_affinities (org_id, member_id, session_hash, account_id, expires_at) " +
                    "SELECT a.org_id, gen_random_uuid(), md5(a.id || '-' || i), a.id, now() + INTERVAL '1 hour' " +
                    "FROM sharing_accounts a, generate_series(1, 4) AS i",
            )
        }
        server.database.superuserOutsideTransaction(
            "VACUUM ANALYZE sharing_accounts",
            "VACUUM ANALYZE sharing_slots",
            "VACUUM ANALYZE sharing_affinities",
        )

        val ids = listOf(accountA)
        val hash = sessionHash("session-1")
        val slot = AccountSlot(Uuid.random(), accountA)
        val database = server.koin.get<JdbcDatabase>()
        mapOf<String, StatementBuilder.() -> Statement<*>>(
            "the candidates locked" to { slotLock(orgA, ids) },
            "their slots" to { slotsActive(orgA, ids) },
            "the session's account" to { slotRemembered(orgA, memberA, hash) },
            "the expired slots dropped" to { slotPurge(orgA) },
            "the slot taken" to { slotInsert(orgA, slot) },
            "the expired sessions dropped" to { affinityPurge(orgA) },
            "the session known" to { affinityKnown(orgA, memberA, hash) },
            "the member's sessions" to { memberAffinities(orgA, memberA) },
            "the org's live sessions" to { orgAffinities(orgA) },
            "the session remembered" to { affinityRemember(orgA, memberA, hash, accountA) },
            "the slot renewed" to { slotRenew(orgA, slot.id) },
            "the slot given back" to { slotRelease(orgA, slot.id) },
            "the org's slots" to { slotsActive(orgA) },
            "the sessions of the pacing" to { affinitiesActive(orgA, AccountSlotRepository.AFFINITY) },
        ).forEach { (what, statement) ->
            val plan = database.inOrg(orgA) { plan(statement).also { rollback() } }
            assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
        }
    }
}
