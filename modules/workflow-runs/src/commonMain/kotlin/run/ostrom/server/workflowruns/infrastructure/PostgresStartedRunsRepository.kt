// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.lockUntilCommit
import run.ostrom.server.kernel.database.rowBefore
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.database.write
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.repositories.KeyedAdd
import run.ostrom.server.workflowruns.domain.repositories.KeyedRunsRepository
import run.ostrom.server.workflowruns.domain.repositories.RunsQuery
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [StartedRunsRepository] and [KeyedRunsRepository] on PostgreSQL (`workflow_runs_started`,
 * [WorkflowRunsMigrations]), every query in a transaction scoped to one organization by `ostrom.org_id`, which row
 * level security enforces. The audit line of a start is appended in the transaction that stores the run
 * ([withOrganizationRecording]).
 *
 * @param database The serving role's pool.
 * @param audit The organization's signed audit log, written in the transaction that starts a run.
 */
class PostgresStartedRunsRepository(
    private val database: JdbcDatabase,
    private val audit: TransactionalAuditLog,
) : StartedRunsRepository, KeyedRunsRepository {
    override suspend fun add(orgId: Uuid, run: WorkflowRun, startedAt: Instant, graph: RunGraph, audit: RunAudit) {
        database.withOrganizationRecording(this.audit, orgId, run.id, listOf(audit)) { record ->
            write { insertRun(orgId, run, startedAt, graph) }
            record()
        }
    }

    override suspend fun addKeyed(
        orgId: Uuid,
        run: WorkflowRun,
        startedAt: Instant,
        graph: RunGraph,
        audit: RunAudit,
        key: String?,
        maxUnfinished: Int,
    ): KeyedAdd = database.withOrganizationRecording(this.audit, orgId, run.id, listOf(audit)) { record ->
        // One starter's starts wait for each other: the look-up, the count and the insert decide together.
        lockUntilCommit("workflow-runs:$orgId:${run.startedBy}")
        val existing = key?.let { keyedRun(orgId, run.startedBy, it).firstOrNull() }
        val unfinished = unfinishedRuns(orgId, run.startedBy, maxUnfinished).toList().size
        when {
            existing != null -> KeyedAdd.Existing(existing)
            unfinished >= maxUnfinished -> KeyedAdd.AtLimit
            else -> {
                write { insertRun(orgId, run, startedAt, graph, key) }
                record()
                KeyedAdd.Added(run)
            }
        }
    }

    override suspend fun addChild(orgId: Uuid, child: WorkflowRun, seq: Int, graph: RunGraph, audit: RunAudit): String =
        database.withOrganizationRecording(this.audit, orgId, child.id, listOf(audit)) { record ->
            val parent = "${child.parentRunId}"
            // A second start of the same visit stores nothing and returns no row: `ON CONFLICT (org_id, parent_run_id,
            // parent_seq) DO UPDATE … WHERE FALSE`, the sqlx4k `DO NOTHING` on that target alone.
            val stored = R.upsertReturning(
                R.orgId, R.parentRunId, R.parentSeq,
                returning = listOf(R.id),
                onUpdate = { it[R.id] = R.id },
                where = { Op.FALSE },
            ) {
                it.startedRow(orgId, child, Instant.parse(child.startedAt), graph)
                it[parentRunId] = parent
                it[parentSeq] = seq
            }.any()
            // The head moved: the transaction is rolled back and this result dropped, the start runs again.
            if (stored && !record()) return@withOrganizationRecording ""
            childOf(orgId, parent, seq).single()
        }

    override suspend fun find(orgId: Uuid, id: String): WorkflowRun? = database.withOrganization(orgId) {
        findRun(orgId, id).singleOrNull()
    }

    override suspend fun runs(orgId: Uuid, query: RunsQuery): List<WorkflowRun> = database.withOrganization(orgId) {
        runsPage(orgId, query).rows()
    }
}

private val R = WorkflowRunsStarted

private fun decode(body: String): WorkflowRun = OstromJson.decodeFromString(WorkflowRun.serializer(), body)

/** What every stored run sets: its key, its columns, its body and graph, due at once for the engine. */
private fun UpdateBuilder<*>.startedRow(orgId: Uuid, run: WorkflowRun, startedAt: Instant, graph: RunGraph) {
    this[R.orgId] = orgId
    this[R.id] = run.id
    this[R.workflowId] = run.workflowId
    this[R.status] = run.status.value
    this[R.startedAt] = startedAt
    this[R.body] = jsonbOf(OstromJson.encodeToString(WorkflowRun.serializer(), run))
    this[R.graph] = jsonbOf(OstromJson.encodeToString(RunGraph.serializer(), graph))
    this[R.nextStepAt] = startedAt
}

/** Stores a run, due at once for the engine, under [key] when it starts under one. */
internal fun StatementBuilder.insertRun(
    orgId: Uuid,
    run: WorkflowRun,
    startedAt: Instant,
    graph: RunGraph,
    key: String? = null,
) = R.insert {
    it.startedRow(orgId, run, startedAt, graph)
    if (key != null) it[idempotencyKey] = key
}

/** The run a starter started under a key (on `workflow_runs_started_key`). */
internal fun keyedRun(orgId: Uuid, startedBy: String, key: String): Read<WorkflowRun> = Read(
    R.select(R.body).where { (R.orgId eq orgId) and (R.startedBy eq startedBy) and (R.idempotencyKey eq key) },
) { decode(it[R.body]) }

/** A starter's unfinished runs, child runs aside, up to the cap (on `workflow_runs_started_unfinished`). */
internal fun unfinishedRuns(orgId: Uuid, startedBy: String, limit: Int): Query = R.select(R.id).where {
    (R.orgId eq orgId) and (R.startedBy eq startedBy) and (R.status inList UNFINISHED) and R.parentRunId.isNull()
}.limit(limit)

/** The child run of a parent's visit. */
internal fun childOf(orgId: Uuid, parentRunId: String, parentSeq: Int): Read<String> = Read(
    R.select(R.id).where { (R.orgId eq orgId) and (R.parentRunId eq parentRunId) and (R.parentSeq eq parentSeq) },
) { it[R.id] }

/** One run. */
internal fun findRun(orgId: Uuid, id: String): Read<WorkflowRun> =
    Read(R.select(R.body).where { (R.orgId eq orgId) and (R.id eq id) }) { decode(it[R.body]) }

/**
 * A page of runs, newest first.
 *
 * @param orgId The organization.
 * @param query Which filters apply.
 *
 * @return The read.
 */
internal fun runsPage(orgId: Uuid, query: RunsQuery): Read<WorkflowRun> {
    val filters = listOfNotNull(
        R.orgId eq orgId,
        query.workflowId?.let { R.workflowId eq it },
        query.startedBy?.let { R.startedBy eq it },
        query.status?.let { R.status eq it.value },
        query.before?.let { before ->
            val cursor = R.select(R.startedAt, R.id).where { (R.orgId eq orgId) and (R.id eq before) }
            rowBefore(listOf(R.startedAt, R.id), cursor)
        },
    )
    return Read(
        R.select(R.body).where { filters.reduce { all, next -> all and next } }
            .orderBy(R.startedAt to SortOrder.DESC, R.id to SortOrder.DESC).limit(query.limit),
    ) { decode(it[R.body]) }
}

private val UNFINISHED = listOf("queued", "running", "waiting")
