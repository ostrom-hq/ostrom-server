// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.core.api.models.WorkflowRun
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.workflowruns.domain.repositories.RunsQuery
import dev.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [StartedRunsRepository] on PostgreSQL (`workflow_runs_started`, [WorkflowRunsMigrations]), every query in a
 * transaction scoped to one organization by `ostrom.org_id`, which row level security enforces.
 *
 * @param driver The connection pool.
 */
class PostgresStartedRunsRepository(private val driver: Driver) : StartedRunsRepository {
    override suspend fun add(orgId: Uuid, run: WorkflowRun, startedAt: Instant) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(StartedRunQueries.INSERT).bind("org_id", orgId).bind("id", run.id)
                    .bind("workflow_id", run.workflowId).bind("status", run.status.value)
                    .bind("started_at", startedAt)
                    .bind("body", OstromJson.encodeToString(WorkflowRun.serializer(), run)),
            ).getOrThrow()
        }
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
    /** Stores a run. */
    const val INSERT: String =
        "INSERT INTO workflow_runs_started (org_id, id, workflow_id, status, started_at, body) " +
            "VALUES (:org_id, :id, :workflow_id, :status, :started_at, CAST(:body AS JSONB))"

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
