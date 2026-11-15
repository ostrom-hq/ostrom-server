// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.present
import run.ostrom.server.kernel.database.rowBefore
import run.ostrom.server.kernel.database.write
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudTransfer
import run.ostrom.server.runners.domain.CloudTransfersRepository
import run.ostrom.server.runners.domain.SessionChange
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.TransferCompletion
import run.ostrom.server.runners.domain.TransferDirection
import run.ostrom.server.runners.domain.TransferOpening
import run.ostrom.server.runners.domain.TransferQuery
import run.ostrom.server.runners.domain.TransferState
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.ReturningStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudTransfersRepository] on PostgreSQL (`runners_session_transfers`, version 20 of [RunnersMigrations]), confined
 * to the organization like the sessions ([JdbcDatabase.inOrg]). A write that touches a session commits its
 * compare-and-set in the same transaction ([compareAndSetIn]): either both are written or neither.
 *
 * @param database The JDBC pool.
 */
class CloudTransfersRepositoryImpl(private val database: JdbcDatabase) : CloudTransfersRepository {
    override suspend fun find(orgId: Uuid, id: Uuid): CloudTransfer? = database.inOrg(orgId) {
        val t = RunnersSessionTransfers
        t.select(t.columns).where { (t.id eq id) and (t.orgId eq orgId) }.firstOrNull()?.let(::toTransfer)
    }

    override suspend fun list(orgId: Uuid, query: TransferQuery): List<CloudTransfer> =
        database.inOrg(orgId) { transferList(orgId, query).map(::toTransfer) }

    override suspend fun open(transfer: CloudTransfer, now: Instant, session: SessionChange?): TransferOpening =
        database.inOrg(transfer.orgId) {
            val s = RunnersSessionsTable
            // The session's row serializes every transfer asked for it.
            val locked = s.select(s.id).where { (s.id eq transfer.sessionId) and (s.orgId eq transfer.orgId) }
                .forUpdate().firstOrNull()
            if (locked == null) return@inOrg TransferOpening.Conflict
            replayOrConflict(transfer, now)?.let { return@inOrg it }
            // An offer past its validity does not hold the session: it is recorded expired here.
            write { transferExpired(transfer, now) }
            if (!transferOpen(transfer).empty()) return@inOrg TransferOpening.Pending
            if (session != null && compareAndSetIn(session.next, session.nextReconcileAt) == null) {
                return@inOrg TransferOpening.Conflict
            }
            write { transferInsert(transfer) }
            TransferOpening.Opened(transfer)
        }

    /** A key used before replays its transfer (same request) or conflicts; one past its retention is forgotten. */
    private fun JdbcTransaction.replayOrConflict(transfer: CloudTransfer, now: Instant): TransferOpening? {
        val key = transfer.idempotencyKey ?: return null
        val t = RunnersSessionTransfers
        val existing = t.select(t.columns).where {
            (t.orgId eq transfer.orgId) and (t.initiatedBy eq transfer.initiatedBy) and (t.idempotencyKey eq key)
        }.firstOrNull()?.let(::toTransfer)
        return when {
            existing == null -> null
            existing.createdAt + CloudTransfer.KEY_RETENTION <= now -> {
                write { transferKeyForgotten(existing.id) }
                null
            }
            existing.requestDigest == transfer.requestDigest -> TransferOpening.Replayed(existing)
            else -> TransferOpening.KeyConflict
        }
    }

    override suspend fun update(expected: CloudTransfer, next: CloudTransfer, session: SessionChange?): CloudTransfer? =
        attempt(expected.orgId) {
            val stored = returned { transferWritten(expected, next) }.firstOrNull()?.let(::toTransfer) ?: throw Lost()
            if (session != null && compareAndSetIn(session.next, session.nextReconcileAt) == null) throw Lost()
            stored
        }

    override suspend fun complete(
        expected: CloudSession,
        next: CloudSession,
        transfer: CloudTransfer,
    ): TransferCompletion {
        // Guarded by the version the step observed: a transfer cancelled or failed since does not complete.
        val observed = transfer.copy(version = transfer.version - 1)
        val stored = attempt(transfer.orgId) {
            if (returned { transferWritten(observed, transfer) }.isEmpty()) throw Lost()
            val session = compareAndSetIn(next, next.updatedAt) ?: throw Lost()
            // Nothing issued to the former owner survives the change: their terminal tickets go.
            write { ticketsForgotten(expected.orgId, expected.id) }
            session
        }
        return if (stored == null) {
            TransferCompletion.Conflict
        } else {
            TransferCompletion.Completed(transfer.copy(version = transfer.version), stored)
        }
    }

    /** Runs [block] in the organization's transaction; [Lost] rolls it back and gives null. */
    private suspend fun <T : Any> attempt(orgId: Uuid, block: JdbcTransaction.() -> T): T? = try {
        database.inOrg(orgId, block)
    } catch (_: Lost) {
        null
    }

    /** A compare-and-set lost: the transaction rolls back. */
    private class Lost : Exception()
}

/** The transfers of a member, newest first, as [query] asks. */
internal fun transferList(orgId: Uuid, query: TransferQuery): Query {
    val t = RunnersSessionTransfers
    val side: Op<Boolean> = when (query.direction) {
        TransferDirection.INCOMING -> t.toMemberId eq query.memberId
        TransferDirection.OUTGOING -> t.fromMemberId eq query.memberId
        TransferDirection.BOTH -> (t.toMemberId eq query.memberId) or (t.fromMemberId eq query.memberId)
    }
    val states: Op<Boolean> =
        if (query.states.isEmpty()) Op.TRUE else t.state inList query.states.map { it.wire }
    val session: Op<Boolean> = if (query.sessionId == null) Op.TRUE else t.sessionId eq query.sessionId
    val cursor: Op<Boolean> = if (query.before == null) {
        Op.TRUE
    } else {
        rowBefore(
            listOf(t.createdAt, t.id),
            t.select(t.createdAt, t.id).where { (t.id eq query.before) and (t.orgId eq orgId) },
        )
    }
    return t.select(t.columns).where { (t.orgId eq orgId) and side and states and session and cursor }
        .orderBy(t.createdAt to SortOrder.DESC, t.id to SortOrder.DESC)
        .limit(query.limit)
}

/** The session's offers past their validity, recorded expired. */
internal fun StatementBuilder.transferExpired(transfer: CloudTransfer, now: Instant): UpdateStatement {
    val t = RunnersSessionTransfers
    return t.update(
        {
            (t.orgId eq transfer.orgId) and (t.sessionId eq transfer.sessionId) and (t.state eq TransferState.OFFERED.wire) and
                (t.expiresAt lessEq now)
        },
    ) {
        it[state] = TransferState.EXPIRED.wire
        it[version] = t.version + 1
        it[updatedAt] = now
    }
}

/** The session's transfers still open (offered or accepted), one at most. */
internal fun transferOpen(transfer: CloudTransfer): Query {
    val t = RunnersSessionTransfers
    return t.select(t.id)
        .where {
            (t.orgId eq transfer.orgId) and (t.sessionId eq transfer.sessionId) and
                (t.state inList listOf(TransferState.OFFERED.wire, TransferState.ACCEPTED.wire))
        }
        .limit(1)
}

/** A key past its retention, forgotten by the transfer that used it. */
internal fun StatementBuilder.transferKeyForgotten(id: Uuid): UpdateStatement {
    val t = RunnersSessionTransfers
    return t.update({ t.id eq id }) { it[idempotencyKey] = null }
}

/** A new transfer. */
internal fun StatementBuilder.transferInsert(t: CloudTransfer): InsertStatement<Number> =
    RunnersSessionTransfers.insert {
        it[id] = t.id
        it[orgId] = t.orgId
        it[sessionId] = t.sessionId
        it[fromMemberId] = t.fromMemberId
        it[toMemberId] = t.toMemberId
        it[initiatedBy] = t.initiatedBy
        it[state] = t.state.wire
        it[resume] = t.resume
        it[version] = t.version
        it[createdAt] = t.createdAt
        it[updatedAt] = t.updatedAt
        it[expiresAt] = t.expiresAt
        it[decidedBy] = t.decidedBy
        it[decidedAt] = t.decidedAt
        it[completedAt] = t.completedAt
        it[atEpoch] = t.atEpoch
        it[failureCode] = t.failure?.code
        it[failureMessage] = t.failure?.message
        it[idempotencyKey] = t.idempotencyKey
        it[requestDigest] = t.requestDigest
    }

/** A transfer written if nobody moved it since [expected]'s version; returns the row stored. */
internal fun StatementBuilder.transferWritten(expected: CloudTransfer, next: CloudTransfer): ReturningStatement {
    val t = RunnersSessionTransfers
    return t.updateReturning(
        t.columns,
        where = { (t.id eq next.id) and (t.orgId eq next.orgId) and (t.version eq expected.version) },
    ) {
        it[state] = next.state.wire
        it[resume] = next.resume
        it[version] = next.version
        it[updatedAt] = next.updatedAt
        it[expiresAt] = next.expiresAt
        it[decidedBy] = next.decidedBy
        it[decidedAt] = next.decidedAt
        it[completedAt] = next.completedAt
        it[atEpoch] = next.atEpoch
        it[failureCode] = next.failure?.code
        it[failureMessage] = next.failure?.message
    }
}

/** The terminal tickets of a session, deleted. */
internal fun StatementBuilder.ticketsForgotten(orgId: Uuid, sessionId: Uuid): DeleteStatement =
    RunnersTerminalTickets.deleteWhere {
        (RunnersTerminalTickets.sessionId eq sessionId) and (RunnersTerminalTickets.orgId eq orgId)
    }

private fun toTransfer(row: ResultRow): CloudTransfer {
    val t = RunnersSessionTransfers
    return CloudTransfer(
        id = row[t.id],
        orgId = row[t.orgId],
        sessionId = row[t.sessionId],
        fromMemberId = row[t.fromMemberId],
        toMemberId = row[t.toMemberId],
        initiatedBy = row[t.initiatedBy],
        // A state this version does not know is a failed transfer, never an open one.
        state = TransferState.fromWire(row[t.state]) ?: TransferState.FAILED,
        resume = row[t.resume],
        version = row[t.version],
        createdAt = row[t.createdAt],
        updatedAt = row[t.updatedAt],
        expiresAt = row[t.expiresAt],
        decidedBy = row[t.decidedBy],
        decidedAt = row[t.decidedAt],
        completedAt = row[t.completedAt],
        atEpoch = row[t.atEpoch],
        failure = row[t.failureCode]?.let { SessionFailure(it, row.present(t.failureMessage, t.failureCode)) },
        idempotencyKey = row[t.idempotencyKey],
        requestDigest = row[t.requestDigest],
    )
}
