// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.domain.repositories

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The end of a session as its gates see it, inside one transaction of the gates (AUDIT-SERVEUR-3 B2). The end of a
 * session and every gate asked in it take the same lock of the session first: the end marks the session ended and
 * reads its gates for the receipt, a gate checks the mark before it joins the session. A gate is then either read by
 * the end, or refused.
 */
interface GateSessionEnds {
    /**
     * Serializes the transactions of the same session (a PostgreSQL advisory lock), until the transaction ends. Taken
     * before any other lock of the transaction.
     *
     * @param orgId The organization.
     * @param sessionId The session segment.
     */
    suspend fun lockSession(orgId: Uuid, sessionId: String)

    /**
     * Reads when the end of a session read its gates.
     *
     * @param orgId The organization.
     * @param sessionId The session segment.
     * @return When it ended, or null while it has not.
     */
    suspend fun sessionEndedAt(orgId: Uuid, sessionId: String): Instant?

    /**
     * Marks a session ended; a session already marked keeps its first time.
     *
     * @param orgId The organization.
     * @param sessionId The session segment.
     * @param at When it ended.
     */
    suspend fun markSessionEnded(orgId: Uuid, sessionId: String, at: Instant)

    /**
     * Lists the gates a session asked or joined, oldest first.
     *
     * @param orgId The organization.
     * @param sessionId The session segment.
     * @return The gates.
     */
    suspend fun gatesOfSession(orgId: Uuid, sessionId: String): List<Gate>

    /**
     * Reads the requests of several gates in one read, each gate's in the approvers' order.
     *
     * @param orgId The organization.
     * @param gateIds The gates, at least one.
     * @return The requests.
     */
    suspend fun requestsOfGates(orgId: Uuid, gateIds: List<Uuid>): List<ApprovalRequest>
}
