// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.RequestStatus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.rowNumber
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

// The reads of the approval requests (GatesRepositoryImpl), one function each so that GatesQueryPlansTest explains
// exactly what the repository sends. Identities compare without case everywhere (`SelfApproval.same`): `approver_key`
// is the lowered approver, so the comparison with `lower(?)` is a leakproof `=`, an index condition under row level
// security.

/** The most requests a gate's read returns. */
internal const val MAX_REQUESTS = 100

/** The most sessions an approver's read returns: the receipts they may read, never more than these. */
internal const val MAX_APPROVED_SESSIONS = 10_000

/** One approval request (not locked: its gate is the lock). */
internal fun requestRead(orgId: Uuid, id: Uuid): Read<ApprovalRequest> {
    val r = GatesRequests
    return Read(r.select(REQUEST_COLUMNS).where { (r.orgId eq orgId) and (r.id eq id) }) { it.toRequest() }
}

/** The approval requests of one gate, in the approvers' order. */
internal fun requestsOfGate(orgId: Uuid, gateId: Uuid): Read<ApprovalRequest> {
    val r = GatesRequests
    val query = r.select(REQUEST_COLUMNS).where { (r.orgId eq orgId) and (r.gateId eq gateId) }
        .orderBy(r.position)
        .limit(MAX_REQUESTS)
    return Read(query) { it.toRequest() }
}

/** The approval requests of several gates, each gate's in the approvers' order. */
internal fun gatesRequestsRead(orgId: Uuid, gateIds: List<Uuid>): Read<ApprovalRequest> {
    val r = GatesRequests
    val query = r.select(REQUEST_COLUMNS).where { (r.orgId eq orgId) and (r.gateId inList gateIds) }
        .orderBy(r.gateId to SortOrder.ASC, r.position to SortOrder.ASC)
        .limit(gateIds.size * MAX_REQUESTS)
    return Read(query) { it.toRequest() }
}

/**
 * A page of approval requests, newest first, at most [limit]: [approverId]'s (any case) when given, of one [status]
 * when given, older than the request [before] when given (none when it names no request of the organization).
 */
internal fun approvalsPage(
    orgId: Uuid,
    approverId: String?,
    status: RequestStatus?,
    limit: Int,
    before: Uuid?,
): Read<ApprovalRequest> {
    val r = GatesRequests
    val filters = listOfNotNull<Op<Boolean>>(
        r.orgId eq orgId,
        approverId?.let { r.approverKey eq stringParam(it).lowerCase() },
        status?.let { r.status eq it.name },
        before?.let { cursor ->
            rowBefore(
                listOf(r.createdAt, r.id),
                r.select(r.createdAt, r.id).where { (r.orgId eq orgId) and (r.id eq cursor) },
            )
        },
    )
    val query = r.select(REQUEST_COLUMNS).where { filters.reduce { all, next -> all and next } }
        .orderBy(r.createdAt to SortOrder.DESC, r.id to SortOrder.DESC)
        .limit(limit)
    return Read(query) { it.toRequest() }
}

/**
 * The approvers of several gates, at most [MAX_REQUESTS] each as when a gate's requests are read. The cap is per gate,
 * never one limit for all of them: a gate with many requests cannot push the next gates out of the result.
 */
internal fun approversRead(orgId: Uuid, gateIds: List<Uuid>): Read<Pair<Uuid, String>> {
    val r = GatesRequests
    val rank = rowNumber().over().partitionBy(r.gateId).orderBy(r.position).alias("rank")
    val ranked = r.select(r.gateId, r.approverId, r.position, rank)
        .where { (r.orgId eq orgId) and (r.gateId inList gateIds) }
        .alias("ranked")
    val gate = ranked[r.gateId]
    val approver = ranked[r.approverId]
    val query = ranked.select(gate, approver)
        .where { ranked[rank] lessEq longLiteral(MAX_REQUESTS.toLong()) }
        .orderBy(gate to SortOrder.ASC, ranked[r.position] to SortOrder.ASC)
        .limit(gateIds.size * MAX_REQUESTS)
    return Read(query) { it[gate] to it[approver] }
}

/**
 * Whether [approver] (any case) has a request on a gate of [sessionId]: the session's links by their primary key,
 * then the requests of each gate by `gates_requests_by_gate`.
 */
internal fun approvesSession(orgId: Uuid, approver: String, sessionId: String): Read<String> {
    val s = GatesSessions
    val r = GatesRequests
    val query = s.join(r, JoinType.INNER, s.gateId, r.gateId)
        .select(s.sessionId)
        .where {
            (s.orgId eq orgId) and (s.sessionId eq sessionId) and (r.orgId eq orgId) and
                (r.approverKey eq stringParam(approver).lowerCase())
        }
        .limit(1)
    return Read(query) { it[s.sessionId] }
}

/**
 * The sessions of the gates [approver] (any case) has a request on: the approver's requests by the inbox index, then
 * each gate's links by `gates_sessions_by_gate` (migration 9).
 */
internal fun approvedSessions(orgId: Uuid, approver: String): Read<String> {
    val s = GatesSessions
    val r = GatesRequests
    val query = r.join(s, JoinType.INNER, additionalConstraint = { (s.orgId eq r.orgId) and (s.gateId eq r.gateId) })
        .select(s.sessionId)
        .withDistinct()
        .where { (r.orgId eq orgId) and (r.approverKey eq stringParam(approver).lowerCase()) and (s.orgId eq orgId) }
        .limit(MAX_APPROVED_SESSIONS)
    return Read(query) { it[s.sessionId] }
}
