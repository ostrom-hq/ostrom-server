// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.domain

import kotlin.uuid.Uuid

/**
 * Wakes the heartbeats that wait for work (the contract's `runnerHeartbeat?wait=`) as soon as an execution is queued or
 * assigned in their organization. A wake-up is a shortcut, never the truth: the woken heartbeat looks at the database.
 */
interface RunnerWorkSignals {
    /**
     * An execution was queued or assigned in [orgId] on this instance: its waiting heartbeats look now.
     *
     * @param orgId The organization.
     */
    fun arrived(orgId: Uuid)

    /**
     * Waits until work may have arrived in [orgId], on this instance or on another one (seen at the next look at the
     * database), holding no database connection; the caller bounds the wait.
     *
     * @param orgId The organization.
     *
     * @return False at once when too many heartbeats already wait on this instance: the caller answers at once.
     */
    suspend fun await(orgId: Uuid): Boolean

    /** Looks at the database, while heartbeats wait, for work queued on another instance; runs until cancelled. */
    suspend fun watch(): Nothing
}
