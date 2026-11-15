// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.database.written
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.workflowruns.domain.engine.sessionOf
import run.ostrom.server.workflowruns.domain.models.PlayedRun
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.models.RunRef
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.appendTo
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RunEngineRepository] on PostgreSQL: the lease crosses organizations through `workflow_runs_claim_due` only; a run
 * is read, changed and released in a transaction scoped to its organization by `ostrom.org_id`. The audit lines of a
 * change are appended in the transaction that writes it ([withOrganizationRecording]).
 *
 * @param database The serving role's pool.
 * @param audit The organization's signed audit log, written in the transaction that changes a run.
 */
class PostgresRunEngineRepository(
    private val database: JdbcDatabase,
    private val audit: TransactionalAuditLog,
) : RunEngineRepository {
    override suspend fun claimDue(owner: String, now: Instant, until: Instant, limit: Int): List<RunRef> =
        database.query { claimDueRuns(owner, until, now, limit).rows() }

    override suspend fun played(orgId: Uuid, id: String): PlayedRun? = database.withOrganization(orgId) {
        playedRun(orgId, id).singleOrNull()
    }

    override suspend fun ofSession(orgId: Uuid, sessionId: Uuid): PlayedRun? = database.withOrganization(orgId) {
        runOfSession(orgId, sessionId).singleOrNull()
    }

    override suspend fun write(orgId: Uuid, change: RunChange): Boolean =
        database.withOrganizationRecording(audit, orgId, change.next.id, change.audit) { record ->
            val row = lockedRun(orgId, change.next.id).singleOrNull()
            val holds = row != null && row.run == change.expected &&
                (change.owner == null || row.leaseOwner == change.owner)
            val written = holds && begun(orgId, change)
            if (written) {
                apply(orgId, change)
                record()
            }
            written
        }

    override suspend fun release(orgId: Uuid, id: String, owner: String, nextAt: Instant?) {
        database.withOrganization(orgId) {
            R.update({ (R.orgId eq orgId) and (R.id eq id) and (R.leaseOwner eq owner) }) {
                it[leaseOwner] = null
                it[leaseUntil] = null
                it[nextStepAt] = nextAt
            }
        }
    }

    // The visit's key, inserted once: a second insert finds it and the change is dropped. An agent visit's key names
    // its session from the start (the session's agent finds its run by it), before the run itself names it.
    private fun JdbcTransaction.begun(org: Uuid, change: RunChange): Boolean {
        val visit = change.visit ?: return true
        val session = if (visit.kind == WorkflowNodeKind.AGENT) sessionOf(change.next.id, visit.seq) else null
        // `ON CONFLICT DO NOTHING`, bare as it was: the key is the table's only unique constraint.
        return written {
            V.insertIgnore {
                it[orgId] = org
                it[runId] = change.next.id
                it[seq] = visit.seq
                it[nodeId] = visit.nodeId
                it[at] = Instant.parse(visit.startedAt)
                it[sessionId] = session
            }
        } > 0
    }

    private fun JdbcTransaction.apply(orgId: Uuid, change: RunChange) {
        val run = change.next
        val dueAt = change.dueAt
        R.update({ (R.orgId eq orgId) and (R.id eq run.id) }) {
            it[status] = run.status.value
            it[body] = jsonbOf(OstromJson.encodeToString(WorkflowRun.serializer(), run))
            if (dueAt != null) it[nextStepAt] = dueAt
        }
    }
}

private val R = WorkflowRunsStarted
private val V = WorkflowRunsVisits

/** A run locked for its change, with the replica that leases it. */
private class LockedRun(val run: WorkflowRun, val leaseOwner: String?)

/**
 * The runs due now, leased to a replica, across organizations: `workflow_runs_claim_due(owner, until, now, limit)`,
 * the one function that crosses organizations, read as a table.
 *
 * @param owner The leasing replica.
 * @param until When the lease ends.
 * @param now The time the runs are due by.
 * @param limit The most runs.
 *
 * @return The read.
 */
internal fun claimDueRuns(owner: String, until: Instant, now: Instant, limit: Int): Read<RunRef> {
    val due = ClaimedRuns
    val call = ClaimCall(
        due.tableName,
        listOf(
            QueryParameter(owner, TextColumnType()), QueryParameter(until, InstantColumnType()),
            QueryParameter(now, InstantColumnType()), QueryParameter(limit, IntegerColumnType()),
        ),
    )
    return Read(call.select(due.runId, due.orgId)) { RunRef(it[due.orgId], it[due.runId]) }
}

/** What `workflow_runs_claim_due` returns, as a table. */
private object ClaimedRuns : Table("workflow_runs_claim_due") {
    val runId = text("run_id")
    val orgId = uuid("org_id")
}

/** The set-returning function [name] called with [arguments], read as a table of its own name. */
private class ClaimCall(name: String, private val arguments: List<Expression<*>>) : Table(name) {
    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(tableName)
        arguments.appendTo(this, prefix = "(", postfix = ")") { +it }
    }
}

/** A run and its graph. */
internal fun playedRun(orgId: Uuid, id: String): Read<PlayedRun> =
    Read(R.select(R.body, R.graph).where { (R.orgId eq orgId) and (R.id eq id) }) { it.played() }

/** A run locked for its change. */
private fun lockedRun(orgId: Uuid, id: String): Read<LockedRun> = Read(
    R.select(R.body, R.leaseOwner).where { (R.orgId eq orgId) and (R.id eq id) }.forUpdate(),
) { LockedRun(OstromJson.decodeFromString(WorkflowRun.serializer(), it[R.body]), it[R.leaseOwner]) }

/** The run of an agent visit's cloud session, and its graph: the index on the visits' sessions. */
internal fun runOfSession(orgId: Uuid, sessionId: Uuid): Read<PlayedRun> = Read(
    V.join(R, JoinType.INNER, V.orgId, R.orgId, additionalConstraint = { R.id eq V.runId })
        .select(R.body, R.graph)
        .where { (V.orgId eq orgId) and (V.sessionId eq sessionId) }
        .orderBy(V.seq to SortOrder.DESC)
        .limit(1),
) { it.played() }

// A run started before the engine copied no graph: an empty one, which fails it with nothing to play.
private fun ResultRow.played(): PlayedRun {
    val raw = this[R.graph]
    return PlayedRun(
        OstromJson.decodeFromString(WorkflowRun.serializer(), this[R.body]),
        if (raw == null) RunGraph() else OstromJson.decodeFromString(RunGraph.serializer(), raw),
    )
}
