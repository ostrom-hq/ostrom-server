// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.repositories.GateAnnouncements
import run.ostrom.server.gates.domain.repositories.GateSessionEnds
import run.ostrom.server.gates.domain.repositories.GatesTransaction
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One gates transaction ([GatesRepositoryImpl.transaction]), confined to its organization: the Exposed DSL runs in
 * [transaction], the current one. The sessions' ends apart ([SessionEnds]): one class would hold more functions than
 * detekt allows. Its announcements publish in the same transaction ([Announcements]).
 *
 * @param transaction The `inOrg` transaction.
 * @param events The organization's feed.
 */
internal class GatesTransactionImpl(
    private val transaction: JdbcTransaction,
    events: OrgEventPublisher,
) : GatesTransaction,
    GateSessionEnds by SessionEnds(transaction),
    GateAnnouncements by Announcements(transaction, events) {
    override fun lockKey(orgId: Uuid, key: String) = transaction.lockKeyUntilCommit(key)

    override fun byKey(orgId: Uuid, key: String, limit: Int): List<Gate> = gatesByKey(orgId, key, limit).rows()

    override fun lockGate(orgId: Uuid, id: Uuid): Gate? = gateRead(orgId, id, lock = true).firstOrNull()

    override fun request(orgId: Uuid, id: Uuid): ApprovalRequest? = requestRead(orgId, id).firstOrNull()

    override fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest> = requestsOfGate(orgId, gateId).rows()

    override fun insertGate(gate: Gate) {
        transaction.write { gateInsert(gate) }
        transaction.linkSessions(gate)
    }

    override fun addAskers(gate: Gate) {
        transaction.write { askersUpdate(gate) }
        transaction.linkSessions(gate)
    }

    override fun updateGate(gate: Gate) = transaction.write { gateUpdate(gate) }

    override fun insertRequests(requests: List<ApprovalRequest>) =
        requests.forEachIndexed { position, request -> transaction.write { requestInsert(request, position) } }

    override fun updateRequests(requests: List<ApprovalRequest>) =
        requests.forEach { request -> transaction.write { requestUpdate(request) } }
}

// A gate's sessions are set when it opens and only grow when another session joins it (`addAskers`).
// One statement for all of them, as before Exposed (none when there is no session).
private fun JdbcTransaction.linkSessions(gate: Gate) {
    if (gate.sessionIds.isNotEmpty()) write { sessionLinks(gate) }
}

/** AUDIT-SERVEUR-3 B2: the lock and the mark of a session's end, and the reads of its receipt. */
private class SessionEnds(private val transaction: JdbcTransaction) : GateSessionEnds {
    // The sessions module's motif (`sessions:<org>:<root>`), in the gates' own name.
    override fun lockSession(orgId: Uuid, sessionId: String) =
        transaction.lockKeyUntilCommit("gates-session:$orgId:$sessionId")

    override fun sessionEndedAt(orgId: Uuid, sessionId: String): Instant? =
        sessionEndRead(orgId, sessionId).firstOrNull()

    override fun markSessionEnded(orgId: Uuid, sessionId: String, at: Instant) =
        transaction.write { sessionEndInsert(orgId, sessionId, at) }

    // Served by the primary key of the session links (migration 3).
    override fun gatesOfSession(orgId: Uuid, sessionId: String): List<Gate> =
        sessionGatesRead(orgId, sessionId).rows()

    override fun requestsOfGates(orgId: Uuid, gateIds: List<Uuid>): List<ApprovalRequest> =
        gatesRequestsRead(orgId, gateIds).rows()
}

/**
 * The approval events, published last in the gate's transaction (the organization's cursor is the lock every publisher
 * shares, taken last everywhere). Nothing else is read: no other module's lookup holds the gate's locks.
 */
private class Announcements(
    private val transaction: JdbcTransaction,
    private val events: OrgEventPublisher,
) : GateAnnouncements {
    override fun announce(type: OrgEventType, gate: Gate, requests: List<ApprovalRequest>) {
        if (requests.isEmpty()) return
        events.publishIn(transaction, gate.orgId, requests.map { it.event(type, gate) })
    }
}
