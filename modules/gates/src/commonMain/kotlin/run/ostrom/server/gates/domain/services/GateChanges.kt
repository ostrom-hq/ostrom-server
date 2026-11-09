// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.domain.services

import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Wakes the reads that wait on a gate (`GET /gates/{id}?wait=`) as soon as it is answered on this instance. Another
 * instance's answer is seen at the waiting read's next look at the database: a wake-up is a shortcut, never the truth.
 */
interface GateChanges {
    /**
     * A gate changed: the reads waiting on it look again now.
     *
     * @param gateId The gate.
     */
    fun changed(gateId: Uuid)

    /**
     * Waits for [gateId] to change on this instance, at most [within]. It holds no database connection.
     *
     * @param gateId The gate.
     * @param within The longest wait.
     */
    suspend fun await(gateId: Uuid, within: Duration)
}
