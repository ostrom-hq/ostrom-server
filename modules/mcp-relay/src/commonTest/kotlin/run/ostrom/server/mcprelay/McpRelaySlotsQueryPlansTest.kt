// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.mcprelay.infrastructure.countMinute
import run.ostrom.server.mcprelay.infrastructure.lockedServer
import run.ostrom.server.mcprelay.infrastructure.memberStatesQuery
import run.ostrom.server.mcprelay.infrastructure.minuteCalls
import run.ostrom.server.mcprelay.infrastructure.orgHostedAssignmentsQuery
import run.ostrom.server.mcprelay.infrastructure.purgeMinutes
import run.ostrom.server.mcprelay.infrastructure.purgeSlots
import run.ostrom.server.mcprelay.infrastructure.releaseSlot
import run.ostrom.server.mcprelay.infrastructure.renewSlot
import run.ostrom.server.mcprelay.infrastructure.runningSlots
import run.ostrom.server.mcprelay.infrastructure.takeSlot
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * The queries every relayed operation sends for its limits under `EXPLAIN ANALYZE`, as the tenant role under
 * row-level security, over tables where the server's rows are a small part (thousands of servers of another
 * organization, with their slots and minutes): none scans a table.
 */
class McpRelaySlotsQueryPlansTest : RelayPostgresHarness() {
    private val serverA = Uuid.parse("00000000-0000-4000-8000-0000000000c7")

    private fun servers(transport: String = "HTTP") =
        "INSERT INTO mcp_relay_servers (id, org_id, name, transport, url, credential_secret_id, credential_header, " +
            "stdio, sharing, version, created_at) SELECT gen_random_uuid(), ?, 'seed-' || i, " +
            "'$transport', 'https://seed.example.com', NULL, NULL, NULL, 'PERSONAL', 1, now() " +
            "FROM generate_series(1, ?) AS i"

    private fun JdbcTransaction.seedServers(org: Uuid, count: Int, transport: String = "HTTP") = exec(
        servers(transport),
        listOf(UuidColumnType() to org, IntegerColumnType() to count),
    )

    @Test
    fun theQueriesOfAnAdmissionScanNoTable() = serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        server.database.superuserJdbc.query {
            seedServers(Relay.orgB, 5_000)
            exec(
                "INSERT INTO mcp_relay_servers (id, org_id, name, transport, url, credential_secret_id, " +
                    "credential_header, stdio, sharing, version, created_at) VALUES (?, ?, 'grafana', 'HTTP', " +
                    "'https://seed.example.com', NULL, NULL, NULL, 'PERSONAL', 1, now())",
                listOf(UuidColumnType() to serverA, UuidColumnType() to Relay.orgA),
            )
            exec(
                "INSERT INTO mcp_relay_slots (id, org_id, server_id, member_id, expires_at) " +
                    "SELECT gen_random_uuid(), s.org_id, s.id, gen_random_uuid(), now() + INTERVAL '10 minutes' " +
                    "FROM mcp_relay_servers s, generate_series(1, 4) AS i",
            )
            exec(
                "INSERT INTO mcp_relay_minutes (org_id, server_id, minute, member_id, calls) " +
                    "SELECT s.org_id, s.id, i, gen_random_uuid(), 1 FROM mcp_relay_servers s, " +
                    "generate_series(1, 4) AS i",
            )
        }
        server.database.superuserOutsideTransaction(
            "VACUUM ANALYZE mcp_relay_servers",
            "VACUUM ANALYZE mcp_relay_slots",
            "VACUUM ANALYZE mcp_relay_minutes",
        )

        val member = Relay.memberA.memberId
        val statements: Map<String, StatementBuilder.() -> Statement<*>> = mapOf(
            "the server locked" to { lockedServer(Relay.orgA, serverA) },
            "the expired slots dropped" to { purgeSlots(Relay.orgA, serverA) },
            "the past minutes dropped" to { purgeMinutes(Relay.orgA, serverA) },
            "the running read" to { runningSlots(Relay.orgA, serverA, member).query },
            "the member's minute read" to { minuteCalls(Relay.orgA, serverA, member).query },
            "the server's minute read" to { minuteCalls(Relay.orgA, serverA, null).query },
            "the slot taken" to { takeSlot(Relay.orgA, serverA, member, Uuid.random()) },
            "the minute counted" to { countMinute(Relay.orgA, serverA, member) },
            "the slot renewed" to { renewSlot(Relay.orgA, Uuid.random()) },
            "the slot given back" to { releaseSlot(Relay.orgA, Uuid.random()) },
        )
        assertNoScan(server.koin.get<JdbcDatabase>(), statements)
    }

    @Test
    fun theHostedReadsOfAListingAndOfTheAdministrationScanNoTable() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            server.database.superuserJdbc.query {
                seedServers(Relay.orgB, 5_000, "STDIO")
                exec(
                    "INSERT INTO mcp_relay_servers (id, org_id, name, transport, url, credential_secret_id, " +
                        "credential_header, stdio, sharing, version, created_at) VALUES (?, ?, 'fs', 'STDIO', " +
                        "NULL, NULL, NULL, NULL, 'SHARED', 1, now())",
                    listOf(UuidColumnType() to serverA, UuidColumnType() to Relay.orgA),
                )
                exec(
                    "INSERT INTO mcp_relay_assignments (org_id, server_id, member_id, tools, credential_secret_id, " +
                        "assigned_at) SELECT s.org_id, s.id, NULL, NULL, NULL, now() FROM mcp_relay_servers s",
                )
                exec(
                    "INSERT INTO mcp_relay_stdio_states (org_id, server_id, member_id, process_key, state, " +
                        "failure, changed_at) SELECT s.org_id, s.id, gen_random_uuid(), 'k', 'ready', NULL, now() " +
                        "FROM mcp_relay_servers s, generate_series(1, 4) AS i",
                )
            }
            server.database.superuserOutsideTransaction(
                "VACUUM ANALYZE mcp_relay_servers",
                "VACUUM ANALYZE mcp_relay_assignments",
                "VACUUM ANALYZE mcp_relay_stdio_states",
            )
            val statements: Map<String, StatementBuilder.() -> Statement<*>> = mapOf(
                "a member's states" to { memberStatesQuery(Relay.orgA, listOf(serverA), Relay.memberA.memberId) },
                "the org's hosted assignments" to { orgHostedAssignmentsQuery(Relay.orgA, 10_001) },
            )
            assertNoScan(server.koin.get<JdbcDatabase>(), statements)
        }

    private suspend fun assertNoScan(
        database: JdbcDatabase,
        statements: Map<String, StatementBuilder.() -> Statement<*>>,
    ) {
        statements.forEach { (what, statement) ->
            val plan = database.inOrg(Relay.orgA) { plan(statement).also { rollback() } }
            assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
        }
    }
}
