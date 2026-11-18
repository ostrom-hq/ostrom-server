// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.mcprelay.infrastructure.mcpCallsPage
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The MCP calls page of one member and of one server under `EXPLAIN ANALYZE`, as the tenant role under row-level
 * security, over 25 000 calls where the member's and the server's are a small part of their organization's: each is
 * read through its own index, never the organization's calls filtered one by one nor a scan of the table
 * (the database audit of 18/11, TeamCode's `mcp_call_member` and `mcp_call_server`).
 */
class McpCallsQueryPlansTest : RelayPostgresHarness() {
    private val member = Uuid.parse("00000000-0000-4000-8000-0000000000d1")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun calls(org: Uuid, member: String, server: String, count: Int): String =
        "INSERT INTO mcp_relay_calls (id, org_id, member_id, server_id, server_name, operation, tool, outcome, " +
            "started_at, duration_ms, response_bytes) SELECT gen_random_uuid(), CAST('$org' AS uuid), $member, " +
            "gen_random_uuid(), $server, 'CALL', 'search', 'OK', now() - i * INTERVAL '1 second', 5, 100 " +
            "FROM generate_series(1, $count) AS i"

    @Test
    fun aMembersAndAServersCallsAreReadThroughTheirIndexes() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
            server.database.superuserOutsideTransaction(
                calls(Relay.orgB, "gen_random_uuid()", "'seed-' || (i % 20)", 5_000),
                calls(Relay.orgA, "CAST(md5('m' || (i % 40)) AS uuid)", "'seed-' || (i % 20)", 20_000),
                calls(Relay.orgA, "CAST('$member' AS uuid)", "'grafana'", 30),
                "VACUUM ANALYZE mcp_relay_calls",
            )
            val database = server.koin.get<JdbcDatabase>()
            val pages = mapOf(
                "mcp_relay_calls_member" to mcpCallsPage(Relay.orgA, null, 50, server = null, memberId = member),
                "mcp_relay_calls_server" to mcpCallsPage(Relay.orgA, null, 50, server = "grafana", memberId = null),
            )
            for ((index, page) in pages) {
                val plan = database.inOrg(Relay.orgA) { plan { page } }
                assertFalse("Seq Scan on mcp_relay_calls" in plan, "A scan of the table:\n$plan")
                // The organization's index would filter the others' calls out one by one.
                assertTrue(index in plan, "Not $index:\n$plan")
            }
        }
}
