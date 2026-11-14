// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import kotlin.time.Duration

/**
 * Periodic work that one replica runs at a time, as TeamCode's `SqlScheduledWork`: a lease row per job, claimed and
 * released on the database's clock, so several replicas coordinate without a pod identity or an in-memory cursor. A
 * module's background loop calls [runDue] as often as it likes; the job runs at most once per [runDue]'s interval
 * across every replica. The instance module binds it on PostgreSQL.
 */
interface ScheduledWork {
    /**
     * Runs [block] when the job named [name] is due and no other replica holds it: claims the lease until [timeout]
     * plus a 15-second margin, runs [block] within [timeout], then releases the lease until [interval] from the end.
     * A [block] that fails or is cancelled still releases the lease (its failure propagates); a replica that dies
     * holding it loses it when the lease ends.
     *
     * @param name The job, 1 to 100 characters (`memory/settle`, `mcp/retain`).
     * @param interval The time between the end of one run and the start of the next (TeamCode's default: 60 s).
     * @param timeout The longest a run may take, at most 5 minutes (TeamCode's default: 45 s); past it [block] is
     *   cancelled.
     * @param block The work.
     *
     * @return Whether this call ran [block]: false when the job was not due or another replica held it.
     *
     * @throws IllegalArgumentException For a name or a duration out of its bounds.
     */
    suspend fun runDue(
        name: String,
        interval: Duration,
        timeout: Duration,
        block: suspend () -> Unit,
    ): Boolean
}
