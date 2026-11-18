// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.notifications.infrastructure.pendingApprovalRequests
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The read of a decided gate's requests for a yes under `EXPLAIN ANALYZE`, in `inOrg` (the tenant role, row level
 * security applies), over 25 000 deliveries of two organizations: it reads the requests' own rows through an index,
 * never a scan of the organization's queue (the database audit of 18/11).
 */
class DeliveriesQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a1")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b1")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter. One delivery in four is a
    // request for a yes, each request on two channels.
    private fun seed(org: Uuid, count: Int): String =
        "INSERT INTO notifications_deliveries (org_id, id, event_seq, member_id, channel, kind, title, body, " +
            "collapse_key, state, attempts, next_attempt_at, lease_until, created_at, approval_id) " +
            "SELECT CAST('$org' AS uuid), gen_random_uuid(), i, gen_random_uuid(), " +
            "CASE WHEN i % 2 = 0 THEN 'push' ELSE 'slack' END, CASE WHEN i % 4 < 2 THEN 'approval' ELSE 'wait' END, " +
            "'t', 'b', 'k', CASE WHEN i % 3 = 0 THEN 'pending' ELSE 'delivered' END, 0, now(), now(), now(), " +
            "CASE WHEN i % 4 < 2 THEN CAST(md5('$org' || (i / 4)) AS uuid) END FROM generate_series(1, $count) AS i"

    @Test
    fun aDecidedGatesRequestsAreReadThroughTheirIndex() = serverTest(listOf(NotificationsModule)) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        server.database.superuserOutsideTransaction(
            seed(orgB, 20_000),
            seed(orgA, 5_000),
            "VACUUM ANALYZE notifications_deliveries",
        )
        val database = server.koin.get<JdbcDatabase>()
        val requests = setOf(Uuid.random(), Uuid.random())
        val plan = database.inOrg(orgA) { plan { pendingApprovalRequests(orgA, requests) } }
        assertFalse("Seq Scan on notifications_deliveries" in plan, "A scan of the queue:\n$plan")
        // Another index of the organization (its primary key) would walk the organization's whole queue.
        assertTrue("notifications_deliveries_approval" in plan, "Not the requests' own index:\n$plan")
    }
}
