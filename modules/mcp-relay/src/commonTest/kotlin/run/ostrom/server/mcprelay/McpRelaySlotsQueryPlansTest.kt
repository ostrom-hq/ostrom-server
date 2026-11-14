// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.infrastructure.MCP_MINUTE_COUNT_SQL
import run.ostrom.server.mcprelay.infrastructure.MCP_ORG_HOSTED_SQL
import run.ostrom.server.mcprelay.infrastructure.memberStatesSql
import run.ostrom.server.mcprelay.infrastructure.MCP_MINUTE_PURGE_SQL
import run.ostrom.server.mcprelay.infrastructure.MCP_SLOT_INSERT_SQL
import run.ostrom.server.mcprelay.infrastructure.MCP_SLOT_LOCK_SQL
import run.ostrom.server.mcprelay.infrastructure.MCP_SLOT_PURGE_SQL
import run.ostrom.server.mcprelay.infrastructure.MCP_SLOT_RELEASE_SQL
import run.ostrom.server.mcprelay.infrastructure.MCP_SLOT_RENEW_SQL
import run.ostrom.server.mcprelay.infrastructure.MCP_USAGE_SQL
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.infrastructure.inTenant
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
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

    private fun servers(org: Uuid, count: Int): String =
        "INSERT INTO mcp_relay_servers (id, org_id, name, transport, url, credential_secret_id, credential_header, " +
            "stdio, sharing, version, created_at) SELECT gen_random_uuid(), CAST('$org' AS uuid), 'seed-' || i, " +
            "'HTTP', 'https://seed.example.com', NULL, NULL, NULL, 'PERSONAL', 1, now() " +
            "FROM generate_series(1, $count) AS i"

    @Test
    fun theQueriesOfAnAdmissionScanNoTable() = serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(servers(Relay.orgB, 5_000)).getOrThrow()
        fixtures.execute(
            "INSERT INTO mcp_relay_servers (id, org_id, name, transport, url, credential_secret_id, " +
                "credential_header, stdio, sharing, version, created_at) VALUES (CAST('$serverA' AS uuid), " +
                "CAST('${Relay.orgA}' AS uuid), 'grafana', 'HTTP', 'https://seed.example.com', NULL, NULL, NULL, " +
                "'PERSONAL', 1, now())",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO mcp_relay_slots (id, org_id, server_id, member_id, expires_at) " +
                "SELECT gen_random_uuid(), s.org_id, s.id, gen_random_uuid(), now() + INTERVAL '10 minutes' " +
                "FROM mcp_relay_servers s, generate_series(1, 4) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO mcp_relay_minutes (org_id, server_id, minute, member_id, calls) " +
                "SELECT s.org_id, s.id, i, gen_random_uuid(), 1 FROM mcp_relay_servers s, generate_series(1, 4) AS i",
        ).getOrThrow()
        listOf("mcp_relay_servers", "mcp_relay_slots", "mcp_relay_minutes").forEach {
            fixtures.execute("VACUUM ANALYZE $it").getOrThrow()
        }

        mapOf(
            "the server locked" to MCP_SLOT_LOCK_SQL,
            "the expired slots dropped" to MCP_SLOT_PURGE_SQL,
            "the past minutes dropped" to MCP_MINUTE_PURGE_SQL,
            "the usage read" to MCP_USAGE_SQL,
            "the slot taken" to MCP_SLOT_INSERT_SQL,
            "the minute counted" to MCP_MINUTE_COUNT_SQL,
            "the slot renewed" to MCP_SLOT_RENEW_SQL,
            "the slot given back" to MCP_SLOT_RELEASE_SQL,
        ).forEach { (what, sql) ->
            val plan = plan(server.driver, sql)
            assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
        }
    }

    @Test
    fun theHostedReadsOfAListingAndOfTheAdministrationScanNoTable() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            val fixtures = server.database.superuser
            fixtures.execute(servers(Relay.orgB, 5_000).replace("'HTTP'", "'STDIO'")).getOrThrow()
            fixtures.execute(
                "INSERT INTO mcp_relay_servers (id, org_id, name, transport, url, credential_secret_id, " +
                    "credential_header, stdio, sharing, version, created_at) VALUES (CAST('$serverA' AS uuid), " +
                    "CAST('${Relay.orgA}' AS uuid), 'fs', 'STDIO', NULL, NULL, NULL, NULL, 'SHARED', 1, now())",
            ).getOrThrow()
            fixtures.execute(
                "INSERT INTO mcp_relay_assignments (org_id, server_id, member_id, tools, credential_secret_id, " +
                    "assigned_at) SELECT s.org_id, s.id, NULL, NULL, NULL, now() FROM mcp_relay_servers s",
            ).getOrThrow()
            fixtures.execute(
                "INSERT INTO mcp_relay_stdio_states (org_id, server_id, member_id, process_key, state, failure, " +
                    "changed_at) SELECT s.org_id, s.id, gen_random_uuid(), 'k', 'ready', NULL, now() " +
                    "FROM mcp_relay_servers s, generate_series(1, 4) AS i",
            ).getOrThrow()
            listOf("mcp_relay_servers", "mcp_relay_assignments", "mcp_relay_stdio_states").forEach {
                fixtures.execute("VACUUM ANALYZE $it").getOrThrow()
            }
            mapOf(
                "a member's states" to memberStatesSql(":server_id"),
                "the org's hosted assignments" to MCP_ORG_HOSTED_SQL.replace(":limit", "10001"),
            ).forEach { (what, sql) ->
                val plan = plan(server.driver, sql)
                assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
            }
        }

    private suspend fun plan(driver: Driver, sql: String): String {
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", Relay.orgA)
            .let { if (":server_id" in sql) it.bind("server_id", serverA) else it }
            .let { if (":member_id" in sql) it.bind("member_id", Relay.memberA.memberId) else it }
            .let { if (":id" in sql) it.bind("id", Uuid.random()) else it }
        return driver.inTenant(Relay.orgA) {
            fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
    }
}
