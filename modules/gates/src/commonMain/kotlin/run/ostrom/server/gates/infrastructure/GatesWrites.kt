// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.MultiRowValuesInsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The writes of the gates (GatesTransactionImpl), each a statement builder run with `write { … }`, so that
// GatesQueryPlansTest explains, then rolls back, exactly what the repository sends. Every update filters on the
// organization and the row's key.

/** Stores a new gate. */
internal fun StatementBuilder.gateInsert(gate: Gate): InsertStatement<Number> =
    GatesGates.insert {
        it[id] = gate.id
        it[orgId] = gate.orgId
        it.setGate(gate)
    }

/** Replaces a gate (its sessions' links apart). */
internal fun StatementBuilder.gateUpdate(gate: Gate): UpdateStatement =
    GatesGates.update({ (GatesGates.orgId eq gate.orgId) and (GatesGates.id eq gate.id) }) { it.setGate(gate) }

/** Records who asks a gate (its askers, paths, sessions and launches), and nothing else of it. */
internal fun StatementBuilder.askersUpdate(gate: Gate): UpdateStatement =
    GatesGates.update({ (GatesGates.orgId eq gate.orgId) and (GatesGates.id eq gate.id) }) { it.setAskers(gate) }

/**
 * Links a gate to the sessions that asked or joined it, in one statement (multi-row `VALUES`); a link already there
 * stays (`ON CONFLICT DO NOTHING`). [Gate.sessionIds] is never empty here (GatesTransactionImpl skips the write).
 */
internal fun StatementBuilder.sessionLinks(gate: Gate): MultiRowValuesInsertStatement =
    MultiRowValuesInsertStatement(GatesSessions, ignore = true, shouldReturnGeneratedValues = false).apply {
        val t = GatesSessions
        for (session in gate.sessionIds) {
            addBatch()
            this[t.orgId] = gate.orgId
            this[t.sessionId] = session
            this[t.gateId] = gate.id
            validateLastBatch()
        }
    }

/** Stores a new request, at its approver's [position]. */
internal fun StatementBuilder.requestInsert(request: ApprovalRequest, position: Int): InsertStatement<Number> =
    GatesRequests.insert {
        it[id] = request.id
        it[gateId] = request.gateId
        it[orgId] = request.orgId
        it[this.position] = position
        it[approverId] = request.approverId
        it[status] = request.status.name
        it[answeredAt] = request.answeredAt
        it[channel] = request.channel?.name
        it[createdAt] = request.createdAt
    }

/** Records a request's answer: its status, when and where. */
internal fun StatementBuilder.requestUpdate(request: ApprovalRequest): UpdateStatement =
    GatesRequests.update({ (GatesRequests.orgId eq request.orgId) and (GatesRequests.id eq request.id) }) {
        it[status] = request.status.name
        it[answeredAt] = request.answeredAt
        it[channel] = request.channel?.name
    }

/** Marks a session ended; a session already marked keeps its first time (`ON CONFLICT DO NOTHING`). */
internal fun StatementBuilder.sessionEndInsert(orgId: Uuid, sessionId: String, at: Instant): InsertStatement<Long> =
    GatesSessionEnds.insertIgnore {
        it[this.orgId] = orgId
        it[this.sessionId] = sessionId
        it[endedAt] = at
    }
