// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.write
import run.ostrom.server.kernel.database.written
import run.ostrom.server.runners.domain.Assignment
import run.ostrom.server.runners.domain.MachineExecutionState
import run.ostrom.server.runners.domain.MachineReport
import run.ostrom.server.runners.domain.RunnerAssignmentsRepository
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.InsertSelectStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.core.Table
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RunnerAssignmentsRepository] on PostgreSQL (`runners_assignments`, migration 6 of [RunnersMigrations]), a tenant
 * table: every read and write runs confined to the organization ([JdbcDatabase.inOrg], S2-01), so a runner never sees
 * another organization's executions. A runner's last heartbeat is read from `runners_registered`, a tenant table too.
 *
 * @param database The JDBC pool.
 */
class RunnerAssignmentsRepositoryImpl(private val database: JdbcDatabase) : RunnerAssignmentsRepository {
    override suspend fun assign(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant, onlineSince: Instant) {
        database.inOrg(orgId) { write { assignmentInsert(orgId, sessionId, epoch, now, onlineSince) } }
    }

    override suspend fun find(orgId: Uuid, sessionId: Uuid, epoch: Long): Assignment? = database.inOrg(orgId) {
        val a = RunnersAssignments
        assignments().where { (a.orgId eq orgId) and (a.sessionId eq sessionId) and (a.epoch eq epoch) }
            .firstOrNull()?.toAssignment()
    }

    override suspend fun stop(orgId: Uuid, sessionId: Uuid, now: Instant) {
        database.inOrg(orgId) { write { assignmentStop(orgId, sessionId, now) } }
    }

    override suspend fun drop(orgId: Uuid, sessionId: Uuid, now: Instant, onlineSince: Instant): Boolean =
        database.inOrg(orgId) {
            write { assignmentDrop(orgId, sessionId, now) }
            if (holders(orgId, sessionId, onlineSince).single() > 0) return@inOrg false
            write { assignmentForget(orgId, sessionId) }
            true
        }

    override suspend fun exchange(orgId: Uuid, runnerId: Uuid, report: MachineReport): List<Assignment> =
        database.inOrg(orgId) {
            // What it reports, whatever its history; an execution handed over that it no longer has is missing. The
            // runner's rows are locked while they are read, so that a stop or a delivery cannot slip between the
            // reading and the write; only the rows that change are written.
            val a = RunnersAssignments
            held(orgId, runnerId).forEach { row ->
                val key = row[a.sessionId] to row[a.epoch]
                val state = stateAfter(row, report.executions[key])
                val holds = key.first in report.workspaces
                if (state != row[a.state] || holds != row[a.holdsWorkspace]) {
                    write { assignmentReport(orgId, key.first, key.second, state, holds) }
                }
            }
            // One waiting execution at a time, the oldest, so that waiting work spreads over the online runners.
            assignmentWaiting(orgId, runnerId).firstOrNull()?.let { waiting ->
                write { assignmentTake(orgId, runnerId, waiting[a.sessionId], waiting[a.epoch]) }
            }
            assignments().where { (a.orgId eq orgId) and (a.runnerId eq runnerId) }.orderBy(a.createdAt)
                .map { it.toAssignment() }
        }

    override suspend fun delivered(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant) {
        database.inOrg(orgId) { write { assignmentDelivered(orgId, sessionId, epoch, now) } }
    }

    override suspend fun hasWork(orgId: Uuid): Boolean = database.inOrg(orgId) { !assignmentWork(orgId).empty() }

    private fun ResultRow.toAssignment(): Assignment {
        val a = RunnersAssignments
        return Assignment(
            sessionId = this[a.sessionId],
            epoch = this[a.epoch],
            runnerId = this[a.runnerId],
            runnerSeenAt = this[RunnersRegistered.lastSeenAt],
            state = this[a.state]?.let { MachineExecutionState.of(it) },
            delivered = this[a.deliveredAt] != null,
            stopped = this[a.stoppedAt] != null,
            dropped = this[a.droppedAt] != null,
            superseded = this[SUPERSEDED],
        )
    }
}

/** The assignment `n` of the same session, a later execution, of another runner. */
private val LATER = RunnersAssignments.alias("n")

/** Whether another runner took a later execution of the assignment's session. */
private val SUPERSEDED = exists(
    LATER.select(intLiteral(1)).where {
        val a = RunnersAssignments
        (LATER[a.sessionId] eq a.sessionId) and (LATER[a.epoch] greater a.epoch) and LATER[a.runnerId].isNotNull() and
            (LATER[a.runnerId] neq a.runnerId)
    },
).alias("superseded")

/**
 * An assignment with its runner's last heartbeat (a removed runner reads as never seen), and whether another runner
 * took a later execution of its session.
 */
internal fun assignments(): Query {
    val a = RunnersAssignments
    val r = RunnersRegistered
    return a.join(r, JoinType.LEFT, additionalConstraint = { (r.orgId eq a.orgId) and (r.id eq a.runnerId) })
        .select(
            a.sessionId, a.epoch, a.runnerId, a.state, a.deliveredAt, a.stoppedAt, a.droppedAt, r.lastSeenAt,
            SUPERSEDED,
        )
}

/** The state a runner's report leaves an execution in: the reported one, `missing` when handed over, else kept. */
private fun stateAfter(row: ResultRow, reported: MachineExecutionState?): String? {
    val a = RunnersAssignments
    return when {
        reported != null -> reported.wire
        row[a.deliveredAt] != null && row[a.stoppedAt] == null -> MachineExecutionState.MISSING.wire
        else -> row[a.state]
    }
}

/** The runner's executions, locked in key order for the report that follows. */
private fun JdbcTransaction.held(orgId: Uuid, runnerId: Uuid): List<ResultRow> {
    val a = RunnersAssignments
    return a.select(a.sessionId, a.epoch, a.state, a.deliveredAt, a.stoppedAt, a.holdsWorkspace)
        .where { (a.orgId eq orgId) and (a.runnerId eq runnerId) }
        .orderBy(a.sessionId to SortOrder.ASC, a.epoch to SortOrder.ASC)
        .forUpdate()
        .toList()
}

/** Whether runner `r` holds fewer executions not stopped than the capacity it declared (none: no limit). */
private fun hasRoom(): Op<Boolean> {
    val a = RunnersAssignments
    val r = RunnersRegistered
    val h = a.alias("h")
    val running = h.select(h[a.sessionId].count())
        .where { (h[a.orgId] eq r.orgId) and (h[a.runnerId] eq r.id) and h[a.stoppedAt].isNull() }
    return r.capacity.isNull() or (r.capacity greater wrapAsExpression<Int>(running))
}

/**
 * A new execution, for the runner of the session's latest one while it is online and has room, for anyone otherwise.
 */
internal fun StatementBuilder.assignmentInsert(
    orgId: Uuid,
    sessionId: Uuid,
    epoch: Long,
    now: Instant,
    onlineSince: Instant,
): InsertSelectStatement {
    val a = RunnersAssignments
    val r = RunnersRegistered
    val latest = a.join(r, JoinType.INNER, additionalConstraint = { (r.orgId eq a.orgId) and (r.id eq a.runnerId) })
        .select(a.runnerId)
        .where {
            (a.orgId eq orgId) and (a.sessionId eq sessionId) and (r.lastSeenAt greaterEq onlineSince) and hasRoom()
        }
        .orderBy(a.epoch to SortOrder.DESC)
        .limit(1)
    val values = Table.Dual.select(
        QueryParameter(sessionId, UuidColumnType()),
        QueryParameter(epoch, LongColumnType()),
        QueryParameter(orgId, UuidColumnType()),
        wrapAsExpression<Uuid>(latest),
        QueryParameter(now, InstantColumnType()),
    )
    return a.insertIgnore(values, listOf(a.sessionId, a.epoch, a.orgId, a.runnerId, a.createdAt))
}

/** The execution of a session, stopped. */
internal fun StatementBuilder.assignmentStop(orgId: Uuid, sessionId: Uuid, now: Instant): UpdateStatement {
    val a = RunnersAssignments
    return a.update({ (a.orgId eq orgId) and (a.sessionId eq sessionId) }) {
        it[stoppedAt] = coalesce(a.stoppedAt, QueryParameter(now, InstantColumnType()))
    }
}

/** The executions of a session, stopped and dropped. */
internal fun StatementBuilder.assignmentDrop(orgId: Uuid, sessionId: Uuid, now: Instant): UpdateStatement {
    val a = RunnersAssignments
    return a.update({ (a.orgId eq orgId) and (a.sessionId eq sessionId) }) {
        it[stoppedAt] = coalesce(a.stoppedAt, QueryParameter(now, InstantColumnType()))
        it[droppedAt] = coalesce(a.droppedAt, QueryParameter(now, InstantColumnType()))
    }
}

/** The online runners that still hold a session's workspace. */
internal fun holders(orgId: Uuid, sessionId: Uuid, onlineSince: Instant): Read<Long> {
    val a = RunnersAssignments
    val r = RunnersRegistered
    val held = a.sessionId.count()
    val query = a.join(r, JoinType.INNER, additionalConstraint = { (r.orgId eq a.orgId) and (r.id eq a.runnerId) })
        .select(held)
        .where {
            (a.orgId eq orgId) and (a.sessionId eq sessionId) and (a.holdsWorkspace eq true) and
                (r.lastSeenAt greaterEq onlineSince)
        }
    return Read(query) { it[held] }
}

/** The executions of a session, forgotten. */
internal fun StatementBuilder.assignmentForget(orgId: Uuid, sessionId: Uuid): DeleteStatement =
    RunnersAssignments.deleteWhere {
        (RunnersAssignments.orgId eq orgId) and (RunnersAssignments.sessionId eq sessionId)
    }

/** What a runner reports of one execution: the state it is left in, and whether the runner holds the workspace. */
internal fun StatementBuilder.assignmentReport(
    orgId: Uuid,
    sessionId: Uuid,
    epoch: Long,
    state: String?,
    holds: Boolean,
): UpdateStatement {
    val a = RunnersAssignments
    return a.update({ (a.orgId eq orgId) and (a.sessionId eq sessionId) and (a.epoch eq epoch) }) {
        it[a.state] = state
        it[holdsWorkspace] = holds
    }
}

/**
 * The oldest waiting execution a runner may take, locked, skipping what another runner holds: taken by one runner only
 * even when two ask at once, and only while the runner has room (its heartbeats come one after another: its count does
 * not race with itself).
 */
internal fun assignmentWaiting(orgId: Uuid, runnerId: Uuid): Query {
    val a = RunnersAssignments
    val r = RunnersRegistered
    val room = r.select(intLiteral(1)).where { (r.orgId eq orgId) and (r.id eq runnerId) and hasRoom() }
    return a.select(a.sessionId, a.epoch)
        .where { (a.orgId eq orgId) and a.runnerId.isNull() and a.stoppedAt.isNull() and exists(room) }
        .orderBy(a.createdAt)
        .limit(1)
        .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
}

/** A waiting execution, taken by [runnerId]. */
internal fun StatementBuilder.assignmentTake(orgId: Uuid, runnerId: Uuid, sessionId: Uuid, epoch: Long): UpdateStatement {
    val a = RunnersAssignments
    return a.update({ (a.orgId eq orgId) and (a.sessionId eq sessionId) and (a.epoch eq epoch) }) {
        it[a.runnerId] = runnerId
    }
}

/** An execution handed over. */
internal fun StatementBuilder.assignmentDelivered(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant): UpdateStatement {
    val a = RunnersAssignments
    return a.update({ (a.orgId eq orgId) and (a.sessionId eq sessionId) and (a.epoch eq epoch) }) {
        it[deliveredAt] = coalesce(a.deliveredAt, QueryParameter(now, InstantColumnType()))
    }
}

/** An execution not stopped and not handed over yet, through `runners_assignments_undelivered`. */
internal fun assignmentWork(orgId: Uuid): Query {
    val a = RunnersAssignments
    return a.select(intLiteral(1)).where { (a.orgId eq orgId) and a.deliveredAt.isNull() and a.stoppedAt.isNull() }
        .limit(1)
}
