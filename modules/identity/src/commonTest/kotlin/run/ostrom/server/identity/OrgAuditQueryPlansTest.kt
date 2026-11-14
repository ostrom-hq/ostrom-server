// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.OrgAuditQuery
import run.ostrom.server.identity.infrastructure.OrgAuditPage
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The read of `GET /api/v1/audit` under `EXPLAIN ANALYZE`, as the route runs it (`inOrg`: the tenant role, row-level
 * security), over a log where the organization read holds 5 000 lines and another one 20 000: the page walks the
 * unique index `(chain, sequence)` backwards and finds the line before each one through the same index, never a scan
 * of the table. The plans are printed in the test's output.
 */
class OrgAuditQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a2")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b2")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun seed(org: Uuid, count: Int): String =
        "INSERT INTO identity_audit_events (id, chain, sequence, org_id, actor_id, action, target_type, target_id, " +
            "details, occurred_at, previous_hash, hash, signature, key_id) SELECT gen_random_uuid(), '$org', i, " +
            "CAST('$org' AS uuid), NULL, CASE WHEN i % 10 = 0 THEN 'org.renamed' ELSE 'budget.changed' END, 't', " +
            "'x' || i, '{}', now(), md5('p' || i), md5('h' || i), 's', 'k' FROM generate_series(1, $count) AS i"

    private suspend fun JdbcDatabase.plan(query: OrgAuditQuery): String =
        inOrg(orgA) { plan { OrgAuditPage(orgA, query.copy(limit = PAGE + 1)).query() } }

    @Test
    fun theAuditPageAndItsLinksReadTheChainsIndex() = serverTest(listOf(IdentityModule)) { server ->
        // Fixtures only: the lines of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(seed(orgB, 20_000)).getOrThrow()
        fixtures.execute(seed(orgA, 5_000)).getOrThrow()
        fixtures.execute("VACUUM ANALYZE identity_audit_events").getOrThrow()

        val database = server.koin.get<JdbcDatabase>()
        val plans = linkedMapOf(
            "newest page" to database.plan(OrgAuditQuery()),
            "page by its cursor" to database.plan(OrgAuditQuery(beforeSequence = 2_500)),
            "one event name" to database.plan(OrgAuditQuery(action = "org.renamed")),
        )
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        println("-- audit log plans\n$report")
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on identity_audit_events" in plan, "$name scans the table:\n$plan")
            assertTrue("identity_audit_events_chain_sequence_key" in plan, "$name skips the chain's index:\n$plan")
            // The line before each one is looked up for the page's lines only, never the chain read whole.
            assertFalse("Materialize" in plan || "Join Filter" in plan, "$name reads the whole chain:\n$plan")
            // The page is cut before the lateral read: it runs once per line of the page, never once per line older
            // than the cursor (5 000 loops for `?before=5000` on main e362671).
            val loops = LOOPS.findAll(plan).maxOf { it.groupValues[1].toInt() }
            assertTrue(loops <= PAGE + 1, "$name reads the line before $loops lines, not the page's:\n$plan")
        }
    }

    private companion object {
        const val PAGE = 50
        val LOOPS = Regex("loops=(\\d+)")
    }
}
