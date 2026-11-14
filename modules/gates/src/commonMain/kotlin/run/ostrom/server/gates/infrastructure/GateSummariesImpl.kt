// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.kernel.events.GateSummaries
import run.ostrom.server.kernel.events.GateSummary
import kotlin.uuid.Uuid

/**
 * [GateSummaries] on the gates' own reads: each gate by its id, then the requests of all of them in one read. A gate
 * is open while it waits for its answer ([GateStatus.PENDING]); a pending gate has no expiry of its own.
 *
 * @param gates The gates.
 */
class GateSummariesImpl(private val gates: GatesRepository) : GateSummaries {
    override suspend fun of(orgId: Uuid, ids: Set<String>): List<GateSummary> {
        // An id that is not one of ours names no gate. A page names a handful of gates: one read each, by its key.
        val found = ids.mapNotNull(Uuid::parseOrNull).mapNotNull { gates.get(orgId, it) }
        if (found.isEmpty()) return emptyList()
        val pending = gates.requestsOfGates(orgId, found.map { it.id })
            .filter { it.status == RequestStatus.PENDING }
            .groupBy({ it.gateId }, { it.approverId })
        return found.map { gate ->
            GateSummary(
                id = gate.id.toString(),
                open = gate.status == GateStatus.PENDING,
                askedBy = gate.askedBy,
                label = gate.label,
                repo = gate.subject.repo,
                pendingApprovers = pending[gate.id].orEmpty(),
            )
        }
    }
}
