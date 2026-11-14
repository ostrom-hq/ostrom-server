// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.domain.repositories

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.kernel.events.OrgEventType

/**
 * The gates' events in the organization's feed, inside one transaction of the gates: an approver's request asked
 * ([OrgEventType.APPROVAL_REQUESTED]) and answered ([OrgEventType.APPROVAL_DECIDED]). They exist exactly when the
 * change committed, so a module that works from the journal after the fact (the notifications) misses none and never
 * sees one that rolled back.
 */
interface GateAnnouncements {
    /**
     * Publishes the [type] event of each of [requests], after the transaction's other writes. A request whose
     * approver is not a member of the gate's organization has none: nobody would see it.
     *
     * @param type [OrgEventType.APPROVAL_REQUESTED] or [OrgEventType.APPROVAL_DECIDED].
     * @param gate The gate, as written.
     * @param requests Its requests, as written.
     */
    suspend fun announce(type: OrgEventType, gate: Gate, requests: List<ApprovalRequest>)
}
