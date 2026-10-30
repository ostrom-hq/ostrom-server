// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.repositories

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.RequestStatus
import kotlin.uuid.Uuid

/**
 * Storage of gates and their approval requests. Every decision runs in [transaction], with the rows it changes
 * locked: two approvers answering at the same instant, or two tool calls reserving the same yes, are serialized,
 * and only the first wins.
 */
interface GatesRepository {
    /**
     * Runs [block] in one database transaction; an exception rolls everything back.
     *
     * @param block The work, with locking reads and writes.
     * @return What [block] returns.
     */
    suspend fun <T> transaction(block: suspend GatesTransaction.() -> T): T

    /**
     * Reads a gate.
     *
     * @param orgId The caller's organization.
     * @param id The gate.
     * @return The gate, or null when absent or another organization's.
     */
    suspend fun get(orgId: Uuid, id: Uuid): Gate?

    /**
     * Reads a gate's requests, in the approvers' order.
     *
     * @param orgId The organization.
     * @param gateId The gate.
     * @return The requests.
     */
    suspend fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest>

    /**
     * Reads one request with its gate.
     *
     * @param orgId The organization.
     * @param id The request.
     * @return The request and its gate, or null when absent or another organization's.
     */
    suspend fun approval(orgId: Uuid, id: Uuid): ApprovalView?

    /**
     * Lists requests with their gates, newest first.
     *
     * @param orgId The organization.
     * @param approverId Only this approver's, or everyone's when null.
     * @param status Only this status, or any when null.
     * @param limit At most this many (bounded by the caller).
     * @return The requests.
     */
    suspend fun approvals(orgId: Uuid, approverId: String?, status: RequestStatus?, limit: Int): List<ApprovalView>

    /**
     * Reads the approvers of several gates in one read (the yes queue shows a page of them).
     *
     * @param orgId The organization.
     * @param gateIds The gates, at most a page of them.
     * @return Each gate's approvers, in their order; a gate without request, or another organization's, is absent.
     */
    suspend fun approversOf(orgId: Uuid, gateIds: List<Uuid>): Map<Uuid, List<String>>

    /**
     * Lists the gates a session asked or joined, oldest first.
     *
     * @param orgId The organization.
     * @param sessionId The session segment.
     * @return The gates.
     */
    suspend fun ofSession(orgId: Uuid, sessionId: String): List<Gate>
}

/** The reads and writes of one transaction. Locking reads hold their rows until the transaction ends. */
interface GatesTransaction {
    /**
     * Serializes every transaction on the same key (a PostgreSQL advisory lock): two hooks asking for the same action
     * at once create one gate.
     *
     * @param orgId The organization.
     * @param key The gate key.
     */
    suspend fun lockKey(orgId: Uuid, key: String)

    /**
     * Lists the gates opened for a key, newest first.
     *
     * @param orgId The organization.
     * @param key The key.
     * @param limit At most this many.
     * @return The gates.
     */
    suspend fun byKey(orgId: Uuid, key: String, limit: Int): List<Gate>

    /**
     * Reads a gate and locks it.
     *
     * @param orgId The organization.
     * @param id The gate.
     * @return The gate, or null.
     */
    suspend fun lockGate(orgId: Uuid, id: Uuid): Gate?

    /**
     * Reads a request (not locked: its gate is the lock).
     *
     * @param orgId The organization.
     * @param id The request.
     * @return The request, or null.
     */
    suspend fun request(orgId: Uuid, id: Uuid): ApprovalRequest?

    /**
     * Reads a gate's requests.
     *
     * @param orgId The organization.
     * @param gateId The gate.
     * @return The requests, in the approvers' order.
     */
    suspend fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest>

    /**
     * Stores a new gate.
     *
     * @param gate The gate.
     */
    suspend fun insertGate(gate: Gate)

    /**
     * Records who asks a gate (its askers, paths, sessions and launches), and nothing else of it.
     *
     * @param gate The gate with its askers as they are now.
     */
    suspend fun addAskers(gate: Gate)

    /**
     * Replaces a gate.
     *
     * @param gate The gate as it is now.
     */
    suspend fun updateGate(gate: Gate)

    /**
     * Stores new requests.
     *
     * @param requests The requests.
     */
    suspend fun insertRequests(requests: List<ApprovalRequest>)

    /**
     * Replaces requests.
     *
     * @param requests The requests as they are now.
     */
    suspend fun updateRequests(requests: List<ApprovalRequest>)
}
