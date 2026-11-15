// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.ApprovalView
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.repositories.GateAnnouncements
import run.ostrom.server.gates.domain.repositories.GateHistory
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.repositories.GatesTransaction
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.events.DiscardingOrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventPublisher
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GatesRepository] and [GateHistory] on Exposed. Every call is one transaction in [JdbcDatabase.inOrg]: the tenant
 * role with `ostrom.org_id` set, so the row level security policies of the gates tables refuse another organization's
 * rows even if a query forgot the organization; every query filters on it too.
 *
 * @param database The database.
 * @param events The organization's feed, where [GateAnnouncements.announce] publishes in the gate's transaction.
 */
class GatesRepositoryImpl(
    private val database: JdbcDatabase,
    private val events: OrgEventPublisher = DiscardingOrgEventPublisher(),
) : GatesRepository, GateHistory {
    override suspend fun <T> transaction(orgId: Uuid, block: GatesTransaction.() -> T): T =
        database.inOrg(orgId) { GatesTransactionImpl(this, events).block() }

    override suspend fun get(orgId: Uuid, id: Uuid): Gate? =
        database.inOrg(orgId) { gateRead(orgId, id, lock = false).firstOrNull() }

    override suspend fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest> =
        database.inOrg(orgId) { requestsOfGate(orgId, gateId).rows() }

    override suspend fun requestsOfGates(orgId: Uuid, gateIds: List<Uuid>): List<ApprovalRequest> =
        database.inOrg(orgId) { gatesRequestsRead(orgId, gateIds).rows() }

    override suspend fun ofSession(orgId: Uuid, sessionId: String): List<Gate> =
        database.inOrg(orgId) { sessionGatesRead(orgId, sessionId).rows() }

    override suspend fun recent(orgId: Uuid, since: Instant, limit: Int): List<Gate> =
        database.inOrg(orgId) { gatesRecent(orgId, since, limit).rows() }

    override suspend fun approval(orgId: Uuid, id: Uuid): ApprovalView? = database.inOrg(orgId) {
        // The foreign key keeps a request's gate: read as the one gate it must be.
        requestRead(orgId, id).firstOrNull()?.let { request ->
            ApprovalView(request, listOfNotNull(gateRead(orgId, request.gateId, lock = false).firstOrNull()).single())
        }
    }

    override suspend fun approvals(
        orgId: Uuid,
        approverId: String?,
        status: RequestStatus?,
        limit: Int,
        before: Uuid?,
    ): List<ApprovalView> = database.inOrg(orgId) {
        val requests = approvalsPage(orgId, approverId, status, limit, before).rows()
        val gateIds = requests.map { it.gateId }.distinct()
        // One read for every gate of the page (at most `limit` ids): no query per request.
        val gates = if (gateIds.isEmpty()) emptyMap() else gatesByIds(orgId, gateIds).rows().associateBy { it.id }
        // Every request has its gate (foreign key, gates are never deleted).
        requests.map { ApprovalView(it, gates.getValue(it.gateId)) }
    }

    override suspend fun sessionsApprovedBy(orgId: Uuid, approver: String, sessionId: String?): List<String> =
        database.inOrg(orgId) {
            val read = if (sessionId == null) {
                approvedSessions(orgId, approver)
            } else {
                approvesSession(orgId, approver, sessionId)
            }
            read.rows()
        }

    override suspend fun approversOf(orgId: Uuid, gateIds: List<Uuid>): Map<Uuid, List<String>> {
        if (gateIds.isEmpty()) return emptyMap()
        return database.inOrg(orgId) {
            approversRead(orgId, gateIds).rows().groupBy(keySelector = { it.first }, valueTransform = { it.second })
        }
    }
}
