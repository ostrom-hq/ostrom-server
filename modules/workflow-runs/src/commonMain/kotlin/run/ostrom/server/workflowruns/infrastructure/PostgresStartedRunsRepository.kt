// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.repositories.KeyedAdd
import run.ostrom.server.workflowruns.domain.repositories.KeyedRunsRepository
import run.ostrom.server.workflowruns.domain.repositories.RunsQuery
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [StartedRunsRepository] and [KeyedRunsRepository] on PostgreSQL (`workflow_runs_started`,
 * [WorkflowRunsMigrations]), every query in a transaction scoped to one organization by `ostrom.org_id`, which row
 * level security enforces.
 *
 * @param driver The connection pool.
 * @param audit The organization's signed audit log, written in the transaction that starts a run.
 */
class PostgresStartedRunsRepository(
    private val driver: Driver,
    private val audit: TransactionalAuditLog,
) : StartedRunsRepository, KeyedRunsRepository {
    override suspend fun add(orgId: Uuid, run: WorkflowRun, startedAt: Instant, graph: RunGraph, audit: RunAudit) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(StartedRunQueries.INSERT).bind("org_id", orgId).bind("id", run.id)
                    .bind("workflow_id", run.workflowId).bind("status", run.status.value)
                    .bind("started_at", startedAt).bind("due_at", startedAt)
                    .bind("body", OstromJson.encodeToString(WorkflowRun.serializer(), run))
                    .bind("graph", OstromJson.encodeToString(RunGraph.serializer(), graph)),
            ).getOrThrow()
            this.audit.recordRun(tx, orgId, run.id, audit)
        }
    }

    override suspend fun addKeyed(
        orgId: Uuid,
        run: WorkflowRun,
        startedAt: Instant,
        graph: RunGraph,
        audit: RunAudit,
        key: String,
        maxUnfinished: Int,
    ): KeyedAdd = driver.withOrganization(orgId) { tx ->
        // One starter's keyed starts wait for each other: the look-up, the count and the insert decide together.
        tx.fetchAll(
            Statement.create(StartedRunQueries.LOCK_STARTER).bind("key", "workflow-runs:$orgId:${run.startedBy}"),
        ).getOrThrow()
        val existing = tx.fetchAll(
            Statement.create(StartedRunQueries.KEYED).bind("org_id", orgId).bind("started_by", run.startedBy)
                .bind("key", key),
        ).getOrThrow().firstOrNull()
        val unfinished = tx.fetchAll(
            Statement.create(StartedRunQueries.UNFINISHED).bind("org_id", orgId).bind("started_by", run.startedBy)
                .bind("limit", maxUnfinished),
        ).getOrThrow().size
        when {
            existing != null -> KeyedAdd.Existing(
                OstromJson.decodeFromString(WorkflowRun.serializer(), existing.get("body").asString()),
            )
            unfinished >= maxUnfinished -> KeyedAdd.AtLimit
            else -> {
                tx.execute(
                    Statement.create(StartedRunQueries.INSERT_KEYED).bind("org_id", orgId).bind("id", run.id)
                        .bind("workflow_id", run.workflowId).bind("status", run.status.value)
                        .bind("started_at", startedAt).bind("due_at", startedAt)
                        .bind("body", OstromJson.encodeToString(WorkflowRun.serializer(), run))
                        .bind("graph", OstromJson.encodeToString(RunGraph.serializer(), graph)).bind("key", key),
                ).getOrThrow()
                this.audit.recordRun(tx, orgId, run.id, audit)
                KeyedAdd.Added(run)
            }
        }
    }

    override suspend fun addChild(orgId: Uuid, child: WorkflowRun, seq: Int, graph: RunGraph, audit: RunAudit): String =
        driver.withOrganization(orgId) { tx ->
            val startedAt = Instant.parse(child.startedAt)
            val stored = tx.fetchAll(
                Statement.create(StartedRunQueries.INSERT_CHILD).bind("org_id", orgId).bind("id", child.id)
                    .bind("workflow_id", child.workflowId).bind("status", child.status.value)
                    .bind("started_at", startedAt).bind("due_at", startedAt)
                    .bind("body", OstromJson.encodeToString(WorkflowRun.serializer(), child))
                    .bind("graph", OstromJson.encodeToString(RunGraph.serializer(), graph))
                    .bind("parent_run_id", "${child.parentRunId}").bind("parent_seq", seq),
            ).getOrThrow().firstOrNull() != null
            if (stored) this.audit.recordRun(tx, orgId, child.id, audit)
            tx.fetchAll(
                Statement.create(StartedRunQueries.CHILD).bind("org_id", orgId)
                    .bind("parent_run_id", "${child.parentRunId}").bind("parent_seq", seq),
            ).getOrThrow().first().get("id").asString()
        }

    override suspend fun find(orgId: Uuid, id: String): WorkflowRun? = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(Statement.create(StartedRunQueries.FIND).bind("org_id", orgId).bind("id", id)).getOrThrow()
            .firstOrNull()?.let { OstromJson.decodeFromString(WorkflowRun.serializer(), it.get("body").asString()) }
    }

    override suspend fun runs(orgId: Uuid, query: RunsQuery): List<WorkflowRun> = driver.withOrganization(orgId) { tx ->
        var statement = Statement.create(StartedRunQueries.page(query)).bind("org_id", orgId).bind("limit", query.limit)
        query.workflowId?.let { statement = statement.bind("workflow_id", it) }
        query.status?.let { statement = statement.bind("status", it.value) }
        query.before?.let { statement = statement.bind("before", it) }
        tx.fetchAll(statement).getOrThrow().map {
            OstromJson.decodeFromString(WorkflowRun.serializer(), it.get("body").asString())
        }
    }
}

/** The SQL of [PostgresStartedRunsRepository], also read by the query plans test. */
object StartedRunQueries {
    /** Stores a run, due at once for the engine. */
    const val INSERT: String =
        "INSERT INTO workflow_runs_started (org_id, id, workflow_id, status, started_at, body, graph, next_step_at) " +
            "VALUES (:org_id, :id, :workflow_id, :status, :started_at, CAST(:body AS JSONB), CAST(:graph AS JSONB), " +
            ":due_at)"

    /** Stores a child run unless its parent's visit already has one: a row back when stored. */
    const val INSERT_CHILD: String =
        "INSERT INTO workflow_runs_started (org_id, id, workflow_id, status, started_at, body, graph, next_step_at, " +
            "parent_run_id, parent_seq) VALUES (:org_id, :id, :workflow_id, :status, :started_at, " +
            "CAST(:body AS JSONB), CAST(:graph AS JSONB), :due_at, :parent_run_id, :parent_seq) " +
            "ON CONFLICT (org_id, parent_run_id, parent_seq) DO NOTHING RETURNING id"

    /** Holds one starter's keyed starts until the transaction ends. */
    const val LOCK_STARTER: String = "SELECT pg_advisory_xact_lock(hashtext(:key))"

    /** The run a starter started under a key (on `workflow_runs_started_key`). */
    const val KEYED: String =
        "SELECT body FROM workflow_runs_started WHERE org_id = :org_id AND started_by = :started_by " +
            "AND idempotency_key = :key"

    /** A starter's unfinished runs, child runs aside, up to the cap (on `workflow_runs_started_unfinished`). */
    const val UNFINISHED: String =
        "SELECT id FROM workflow_runs_started WHERE org_id = :org_id AND started_by = :started_by " +
            "AND status IN ('queued', 'running', 'waiting') AND parent_run_id IS NULL LIMIT :limit"

    /** Stores a run started under a key, due at once for the engine. */
    const val INSERT_KEYED: String =
        "INSERT INTO workflow_runs_started (org_id, id, workflow_id, status, started_at, body, graph, next_step_at, " +
            "idempotency_key) VALUES (:org_id, :id, :workflow_id, :status, :started_at, CAST(:body AS JSONB), " +
            "CAST(:graph AS JSONB), :due_at, :key)"

    /** The child run of a parent's visit. */
    const val CHILD: String =
        "SELECT id FROM workflow_runs_started WHERE org_id = :org_id AND parent_run_id = :parent_run_id " +
            "AND parent_seq = :parent_seq"

    /** One run. */
    const val FIND: String = "SELECT body FROM workflow_runs_started WHERE org_id = :org_id AND id = :id"

    /**
     * A page of runs, newest first.
     *
     * @param query Which filters are bound.
     * @return The SQL.
     */
    fun page(query: RunsQuery): String {
        val cursor = "(started_at, id) < " +
            "(SELECT started_at, id FROM workflow_runs_started WHERE org_id = :org_id AND id = :before)"
        val where = listOfNotNull(
            "org_id = :org_id",
            "workflow_id = :workflow_id".takeIf { query.workflowId != null },
            "status = :status".takeIf { query.status != null },
            cursor.takeIf { query.before != null },
        )
        return "SELECT body FROM workflow_runs_started WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY started_at DESC, id DESC LIMIT :limit"
    }
}
