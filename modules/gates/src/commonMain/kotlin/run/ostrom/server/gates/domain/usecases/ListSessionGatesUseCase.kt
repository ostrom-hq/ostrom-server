// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.domain.usecases

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.repositories.GatesRepository
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A gate as the receipt of its session cites it (`bridge/index.ts` endSession).
 *
 * @property id The gate.
 * @property actionClass Its action class.
 * @property mode Enforced, shadow or ungated.
 * @property status Its status now.
 * @property receiptId The receipt of its decision, when signed.
 * @property decisions The answer that decided it: none while it is pending, shadow or ungated.
 */
data class SessionGate(
    val id: Uuid,
    val actionClass: String,
    val mode: GateMode,
    val status: GateStatus,
    val receiptId: String?,
    val decisions: List<SessionGateDecision>,
)

/**
 * The answer that decided a gate.
 *
 * @property approvalId The request that was answered.
 * @property by The approver.
 * @property at When.
 * @property approved Yes or no.
 * @property selfApproved True when the asker, sole approver, said yes.
 */
data class SessionGateDecision(
    val approvalId: Uuid,
    val by: String,
    val at: Instant,
    val approved: Boolean,
    val selfApproved: Boolean,
)

/** The gates of a session, for its receipt (sessions module). */
interface ListSessionGatesUseCase {
    /**
     * Lists the gates a session asked or joined.
     *
     * @param orgId The organization.
     * @param sessionId The session segment.
     * @return The gates, oldest first.
     */
    suspend operator fun invoke(orgId: Uuid, sessionId: String): List<SessionGate>

    /**
     * Ends a session on the gates' side and lists its gates for its receipt, in one transaction under the session's
     * lock (AUDIT-SERVEUR-3 B2): a gate asked in the session from now on is refused, and every gate it accepted before
     * is listed. Called once the end is written; a session already ended keeps its first time.
     *
     * @param orgId The organization.
     * @param sessionId The session segment.
     * @param endedAt When it ended.
     * @return The gates, oldest first.
     */
    suspend fun close(orgId: Uuid, sessionId: String, endedAt: Instant): List<SessionGate>
}

/**
 * [ListSessionGatesUseCase] on the repository.
 *
 * @param repository The gates.
 */
class ListSessionGatesUseCaseImpl(private val repository: GatesRepository) : ListSessionGatesUseCase {
    override suspend fun invoke(orgId: Uuid, sessionId: String): List<SessionGate> =
        sessionGatesOf(repository.ofSession(orgId, sessionId)) { repository.requestsOfGates(orgId, it) }

    override suspend fun close(orgId: Uuid, sessionId: String, endedAt: Instant): List<SessionGate> =
        repository.transaction {
            lockSession(orgId, sessionId)
            markSessionEnded(orgId, sessionId, endedAt)
            sessionGatesOf(gatesOfSession(orgId, sessionId)) { requestsOfGates(orgId, it) }
        }

    private suspend fun sessionGatesOf(
        gates: List<Gate>,
        requestsOfGates: suspend (List<Uuid>) -> List<ApprovalRequest>,
    ): List<SessionGate> {
        val decided = gates.filter { it.decidedAt != null }.map { it.id }
        // One read for the answers of every decided gate, never one per gate.
        val requests = if (decided.isEmpty()) emptyMap() else requestsOfGates(decided).groupBy { it.gateId }
        return gates.map { gate ->
            val decisions = decisionsOf(gate, requests[gate.id].orEmpty())
            SessionGate(gate.id, gate.subject.actionClass, gate.mode, gate.status, gate.receiptId, decisions)
        }
    }

    private fun decisionsOf(gate: Gate, requests: List<ApprovalRequest>): List<SessionGateDecision> {
        val at = gate.decidedAt ?: return emptyList()
        return requests
            .filter { it.status == RequestStatus.APPROVED || it.status == RequestStatus.REJECTED }
            .map {
                SessionGateDecision(it.id, it.approverId, at, it.status == RequestStatus.APPROVED, gate.selfApproved)
            }
    }
}
