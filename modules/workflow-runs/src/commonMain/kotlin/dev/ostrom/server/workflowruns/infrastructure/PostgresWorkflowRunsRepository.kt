// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.Notification
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.workflowruns.domain.repositories.RunQuery
import dev.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WorkflowRunsRepository] on PostgreSQL (the tables of [WorkflowRunsMigrations]), every query in a transaction scoped
 * to one organization by `ostrom.org_id`, which row level security enforces.
 *
 * @param driver The connection pool.
 */
class PostgresWorkflowRunsRepository(private val driver: Driver) : WorkflowRunsRepository {
    override suspend fun addCheck(orgId: Uuid, run: CheckRun) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(
                    "INSERT INTO workflow_runs_checks (org_id, id, at, session_id, node_id, body) " +
                        "VALUES (:org_id, :id, :at, :session_id, :node_id, CAST(:body AS JSONB))",
                ).bind("org_id", orgId).bind("id", run.id).bind("at", Instant.parse(run.at))
                    .nullable("session_id", run.sessionId).bind("node_id", run.nodeId)
                    .bind("body", OstromJson.encodeToString(CheckRun.serializer(), run)),
            ).getOrThrow()
        }
    }

    override suspend fun checks(orgId: Uuid, query: RunQuery): List<CheckRun> = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(RunQueries.checks(query).bindQuery(orgId, query)).getOrThrow().map {
            OstromJson.decodeFromString(CheckRun.serializer(), it.get("body").asString())
        }
    }

    override suspend fun addNotification(orgId: Uuid, notification: Notification, actionKey: String): Notification =
        driver.withOrganization(orgId) { tx ->
            val inserted = tx.fetchAll(
                Statement.create(RunQueries.INSERT_NOTIFICATION).bind("org_id", orgId).bind("id", notification.id)
                    .bind("at", Instant.parse(notification.at)).nullable("session_id", notification.sessionId)
                    .bind("notify_id", notification.notifyId).bind("status", notification.status.value)
                    .bind("action_key", actionKey)
                    .bind("body", OstromJson.encodeToString(Notification.serializer(), notification)),
            ).getOrThrow()
            // Two passes of a hook at once: the first one stored stands.
            if (inserted.any()) notification else first(tx, orgId, notification, actionKey)
        }

    override suspend fun updateNotification(orgId: Uuid, notification: Notification) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(RunQueries.UPDATE_NOTIFICATION).bind("org_id", orgId).bind("id", notification.id)
                    .bind("status", notification.status.value)
                    .bind("body", OstromJson.encodeToString(Notification.serializer(), notification)),
            ).getOrThrow()
        }
    }

    private suspend fun first(tx: Transaction, orgId: Uuid, notification: Notification, actionKey: String) =
        tx.fetchAll(
            Statement.create(RunQueries.FIRST_NOTIFICATION).bind("org_id", orgId)
                .nullable("session_id", notification.sessionId).bind("notify_id", notification.notifyId)
                .bind("action_key", actionKey),
        ).getOrThrow().first().let { decodeNotification(it.get("body").asString()) }

    private fun decodeNotification(body: String) = OstromJson.decodeFromString(Notification.serializer(), body)

    override suspend fun notifications(orgId: Uuid, query: RunQuery): List<Notification> =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(RunQueries.notifications(query).bindQuery(orgId, query)).getOrThrow().map {
                decodeNotification(it.get("body").asString())
            }
        }

    private fun String.bindQuery(orgId: Uuid, query: RunQuery): Statement {
        var statement = Statement.create(this).bind("org_id", orgId).bind("limit", query.limit)
        query.sessionId?.let { statement = statement.bind("session_id", it) }
        query.nodeId?.let { statement = statement.bind("node_id", it) }
        query.status?.let { statement = statement.bind("status", it.value) }
        query.before?.let { statement = statement.bind("before", it) }
        return statement
    }

    private fun Statement.nullable(name: String, value: String?): Statement =
        if (value == null) bindNull(name, String::class) else bind(name, value)
}

/** The SQL of the repository, also read by the query plans test. */
object RunQueries {
    /** Stores a notification unless the same action of the same session has one (not counting one not sent). */
    const val INSERT_NOTIFICATION: String =
        "INSERT INTO workflow_runs_notifications (org_id, id, at, session_id, notify_id, status, action_key, body) " +
            "VALUES (:org_id, :id, :at, :session_id, :notify_id, :status, :action_key, CAST(:body AS JSONB)) " +
            "ON CONFLICT (org_id, session_id, notify_id, action_key) " +
            "WHERE session_id IS NOT NULL AND status <> 'not-sent' DO NOTHING RETURNING id"

    /** Writes what became of a claimed notification. */
    const val UPDATE_NOTIFICATION: String =
        "UPDATE workflow_runs_notifications SET status = :status, body = CAST(:body AS JSONB) " +
            "WHERE org_id = :org_id AND id = :id"

    /** The notification an action of a session had first. */
    const val FIRST_NOTIFICATION: String =
        "SELECT body FROM workflow_runs_notifications WHERE org_id = :org_id AND session_id = :session_id " +
            "AND notify_id = :notify_id AND action_key = :action_key AND status <> 'not-sent'"

    /**
     * A page of check runs, newest first.
     *
     * @param query Which filters are bound.
     * @return The SQL.
     */
    fun checks(query: RunQuery): String = page(
        "workflow_runs_checks",
        listOfNotNull(
            "session_id = :session_id".takeIf { query.sessionId != null },
            "node_id = :node_id".takeIf { query.nodeId != null },
        ),
        query.before != null,
    )

    /**
     * A page of notifications, newest first.
     *
     * @param query Which filters are bound.
     * @return The SQL.
     */
    fun notifications(query: RunQuery): String = page(
        "workflow_runs_notifications",
        listOfNotNull(
            "session_id = :session_id".takeIf { query.sessionId != null },
            "status = :status".takeIf { query.status != null },
        ),
        query.before != null,
    )

    private fun page(table: String, filters: List<String>, after: Boolean): String {
        val cursor = "(at, id) < (SELECT at, id FROM $table WHERE org_id = :org_id AND id = :before)"
        val where = (listOf("org_id = :org_id") + filters + listOfNotNull(cursor.takeIf { after }))
        return "SELECT body FROM $table WHERE ${where.joinToString(" AND ")} ORDER BY at DESC, id DESC LIMIT :limit"
    }
}
