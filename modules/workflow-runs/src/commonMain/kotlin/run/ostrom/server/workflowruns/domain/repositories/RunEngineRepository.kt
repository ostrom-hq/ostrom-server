// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.repositories

import run.ostrom.server.workflowruns.domain.models.PlayedRun
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunRef
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What the engine reads and writes of the runs of workflows: leases across organizations, steps inside one. */
interface RunEngineRepository {
    /**
     * Leases the runs due now, of every organization, to one engine replica: a run leased to a replica is skipped by
     * the others (`FOR UPDATE SKIP LOCKED`) until the lease is released or lapses.
     *
     * @param owner The replica.
     * @param now The time.
     * @param until When the lease lapses, should the replica stop.
     * @param limit At most this many.
     *
     * @return The leased runs.
     */
    suspend fun claimDue(owner: String, now: Instant, until: Instant, limit: Int): List<RunRef>

    /**
     * Reads a run and the graph it plays.
     *
     * @param orgId The organization.
     * @param id The run.
     *
     * @return The run, or null when the organization has none of that id.
     */
    suspend fun played(orgId: Uuid, id: String): PlayedRun?

    /**
     * Reads the run one of whose agent visits works in a cloud session, and the graph it plays.
     *
     * @param orgId The organization.
     * @param sessionId The cloud session.
     *
     * @return The run, or null when no visit of the organization's runs worked in it.
     */
    suspend fun ofSession(orgId: Uuid, sessionId: Uuid): PlayedRun?

    /**
     * Writes a change of a run ([RunChange]), with its audit lines, in one transaction of its organization.
     *
     * @param orgId The organization.
     * @param change The change.
     *
     * @return False, with nothing written, when the run changed since it was read, the lease no longer holds, or the
     *   visit was already begun.
     */
    suspend fun write(orgId: Uuid, change: RunChange): Boolean

    /**
     * Ends a replica's lease of a run.
     *
     * @param orgId The organization.
     * @param id The run.
     * @param owner The replica; another replica's lease is left as it is.
     * @param nextAt When the run is due again, or null when nothing is left to play.
     */
    suspend fun release(orgId: Uuid, id: String, owner: String, nextAt: Instant?)
}
