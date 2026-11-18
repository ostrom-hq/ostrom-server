// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.infrastructure.waitsPage
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * The organization's waits pages under `EXPLAIN ANALYZE`, in `inOrg` (the tenant role, row level security applies),
 * over 25 000 waits of two organizations: a page filtered on the organization alone, or with a status, reads the
 * waits in order through an index and stops at its size, never a scan of the table nor a sort of every wait of the
 * organization (the database audit of 18/11, TeamCode's `wait_team_all` and `wait_team`).
 */
class WaitsQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a1")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b1")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun seed(org: Uuid, count: Int): String =
        "INSERT INTO automations_waits (id, org_id, kind, status, session_id, assignee, created_by, question, " +
            "created_at) SELECT gen_random_uuid(), CAST('$org' AS uuid), 'question', " +
            "CASE WHEN i % 10 = 0 THEN 'open' ELSE 'resolved' END, 's-' || (i % 400), 'm' || (i % 30), " +
            "'m' || (i % 30), '{}', now() - i * INTERVAL '1 minute' FROM generate_series(1, $count) AS i"

    @Test
    fun anOrganizationsWaitsPageReadsItsIndexInOrder() = serverTest(listOf(AutomationsModule)) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        server.database.superuserOutsideTransaction(
            seed(orgB, 20_000),
            seed(orgA, 5_000),
            "VACUUM ANALYZE automations_waits",
        )
        val database = server.koin.get<JdbcDatabase>()
        val filters = listOf(WaitFilter(orgA), WaitFilter(orgA, status = WaitStatus.OPEN))
        for (filter in filters) {
            val plan = database.inOrg(orgA) { plan { waitsPage(filter, before = null, limit = 50) } }
            assertFalse("Seq Scan on automations_waits" in plan, "A scan of the table:\n$plan")
            assertFalse("Sort Key" in plan, "A sort of the organization's waits:\n$plan")
        }
    }
}
