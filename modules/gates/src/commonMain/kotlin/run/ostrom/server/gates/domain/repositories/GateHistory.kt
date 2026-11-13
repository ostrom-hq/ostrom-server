// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.domain.repositories

import run.ostrom.server.gates.domain.models.Gate
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The organization's history of boundary actions, as its gates recorded them: every action an agent's hook asked
 * about, gated, shadowed or ungated. The rules' replay reads it to tell what a rule would have done.
 */
interface GateHistory {
    /**
     * Lists the gates opened since a time, newest first.
     *
     * @param orgId The organization; another organization's gates are never read.
     * @param since The oldest opening read.
     * @param limit The gates read at most.
     * @return The gates.
     */
    suspend fun recent(orgId: Uuid, since: Instant, limit: Int): List<Gate>
}
