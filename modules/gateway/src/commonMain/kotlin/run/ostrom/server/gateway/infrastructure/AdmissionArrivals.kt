// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import kotlin.uuid.Uuid

// The writes of a call's arrival and polls (AdmissionQueueRepositoryImpl), one function each so that
// AdmissionQueuePostgresTest explains exactly what the repository sends. Every one filters on the org.

private val WAITING_SECONDS = AdmissionQueueRepository.WAITING_LEASE.inWholeSeconds
private val RUNNING_SECONDS = AdmissionQueueRepository.RUNNING_LEASE.inWholeSeconds

/** The org's expired entries: waits no longer polled, and calls whose replica died. */
internal fun StatementBuilder.admissionPurge(orgId: Uuid): DeleteStatement =
    GatewayAdmissions.deleteWhere { (this.orgId eq orgId) and (expiresAt lessEq databaseNow()) }

/** A call admitted at once, taking [turn] after its member's [before]. */
internal fun StatementBuilder.admissionRun(
    request: AdmissionRequest,
    turn: Long,
    before: Long,
): InsertStatement<Number> =
    GatewayAdmissions.insert {
        arrival(it, request, running = true)
        it[readyAt] = databaseNow()
        it[expiresAt] = databaseNowPlus(RUNNING_SECONDS)
        it[turnTaken] = turn
        it[turnBefore] = before
    }

/** A waiting call admitted, taking [turn] after its member's [before]. */
internal fun StatementBuilder.admissionStart(request: AdmissionRequest, turn: Long, before: Long): UpdateStatement {
    val a = GatewayAdmissions
    return a.update({ (a.orgId eq request.orgId) and (a.id eq request.id) }) {
        it[running] = true
        it[expiresAt] = databaseNowPlus(RUNNING_SECONDS)
        it[turnTaken] = turn
        it[turnBefore] = before
    }
}

/** The member's turn taken: the scope's last. */
internal fun StatementBuilder.admissionTurn(request: AdmissionRequest, turn: Long): UpsertStatement<Long> {
    val t = GatewayAdmissionTurns
    return t.upsert(t.orgId, t.scope, t.memberId) {
        it[orgId] = request.orgId
        it[scope] = request.scope
        it[memberId] = request.memberId
        it[this.turn] = turn
    }
}

/** A call that starts waiting, ready to take a turn in [readyIn] seconds. */
internal fun StatementBuilder.admissionWait(request: AdmissionRequest, readyIn: Long): InsertStatement<Number> =
    GatewayAdmissions.insert {
        arrival(it, request, running = false)
        it[readyAt] = databaseNowPlus(readyIn)
        it[expiresAt] = databaseNowPlus(WAITING_SECONDS)
    }

/** A waiting call that polled, ready to take a turn in [readyIn] seconds. */
internal fun StatementBuilder.admissionPoll(orgId: Uuid, id: Uuid, readyIn: Long): UpdateStatement =
    GatewayAdmissions.update({ (GatewayAdmissions.orgId eq orgId) and (GatewayAdmissions.id eq id) }) {
        it[readyAt] = databaseNowPlus(readyIn)
        it[polledAt] = databaseNow()
        it[expiresAt] = databaseNowPlus(WAITING_SECONDS)
    }

/** The columns a call has from its arrival, its clocks started now. */
private fun GatewayAdmissions.arrival(it: InsertStatement<Number>, request: AdmissionRequest, running: Boolean) {
    it[id] = request.id
    it[orgId] = request.orgId
    it[memberId] = request.memberId
    it[scope] = request.scope
    it[workload] = request.workload.name
    it[this.running] = running
    it[createdAt] = databaseNow()
    it[polledAt] = databaseNow()
}
