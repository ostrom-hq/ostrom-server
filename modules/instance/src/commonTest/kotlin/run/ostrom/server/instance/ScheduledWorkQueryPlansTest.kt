// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance

import run.ostrom.server.instance.infrastructure.scheduledWorkClaim
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The claim every replica makes on each tick of each periodic job (`ScheduledWork.runDue`), under `EXPLAIN ANALYZE`
 * over thousands of jobs: one update by the primary key, never a scan.
 */
class ScheduledWorkQueryPlansTest {
    @Test
    fun theClaimOfALeaseUpdatesItsRowByTheKey() = serverTest(listOf(InstanceModule)) { server ->
        // Fixtures only: other jobs and the statistics.
        val fixtures = server.database.superuser
        fixtures.execute(
            "INSERT INTO instance_scheduled_work (name) SELECT 'job/' || n FROM generate_series(1, 5000) n",
        ).getOrThrow()
        fixtures.execute("VACUUM ANALYZE instance_scheduled_work").getOrThrow()
        val database = server.koin.get<JdbcDatabase>()

        val claim = database.query {
            plan { scheduledWorkClaim("job/42", Uuid.random(), 1.minutes) }.also { rollback() }
        }
        assertFalse("Seq Scan" in claim, "The claim scans instance_scheduled_work:\n$claim")
    }
}
