// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType

/*
 * The tables of [WorkflowRunsMigrations], as Exposed reads and writes them. The migrations create the schema; these
 * objects only name its columns. Every one is a tenant table, read and written in an organization's transaction
 * (`withOrganization`, the serving role with `ostrom.org_id` set), each query filtered on `org_id` too. The JSON
 * documents (`body`, `graph`) are JSONB columns, read as their text and written through [jsonbOf].
 */

/** A `JSONB` column, read as its JSON text. */
private class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): String = value.toString()
}

private fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())

/** `CAST(? AS JSONB)`: [json] bound as text and cast, as a `JSONB` column takes it. */
internal class JsonbValue(private val json: String) : Expression<String>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("CAST(")
        append(QueryParameter(json, TextColumnType()))
        append(" AS JSONB)")
    }
}

/**
 * The value of a `JSONB` column holding [json].
 *
 * @param json The JSON text.
 *
 * @return The expression to assign.
 */
internal fun jsonbOf(json: String): Expression<String> = JsonbValue(json)

/** The runs of workflows, with what the engine needs to play them: the graph, the next look, the lease. */
internal object WorkflowRunsStarted : Table("workflow_runs_started") {
    val orgId = uuid("org_id")
    val id = text("id")
    val workflowId = text("workflow_id")
    val status = text("status")
    val startedAt = instant("started_at")
    val body = jsonb("body")
    val graph = jsonb("graph").nullable()
    val nextStepAt = instant("next_step_at").nullable()
    val leaseOwner = text("lease_owner").nullable()
    val leaseUntil = instant("lease_until").nullable()
    val parentRunId = text("parent_run_id").nullable()
    val parentSeq = integer("parent_seq").nullable()

    // Generated from the body (`body ->> 'startedBy'`): read, never written.
    val startedBy = text("started_by").nullable()

    // Written by a keyed start, null otherwise.
    val idempotencyKey = text("idempotency_key").nullable()

    override val primaryKey = PrimaryKey(orgId, id)
}

/** The key of each visit begun, inserted once, with the cloud session of an agent visit. */
internal object WorkflowRunsVisits : Table("workflow_runs_visits") {
    val orgId = uuid("org_id")
    val runId = text("run_id")
    val seq = integer("seq")
    val nodeId = text("node_id")
    val at = instant("at")
    val sessionId = uuid("session_id").nullable()

    override val primaryKey = PrimaryKey(orgId, runId, seq)
}

/** What the checks and the notifications share, so that their pages are read alike. */
internal abstract class RunRows(name: String) : Table(name) {
    val orgId = uuid("org_id")
    val id = text("id")
    val at = instant("at")
    val sessionId = text("session_id").nullable()
    val body = jsonb("body")

    // Generated from the body (`body ->> 'dev'`): read, never written.
    val dev = text("dev").nullable()

    override val primaryKey = PrimaryKey(orgId, id)
}

/** The check runs the hooks reported. */
internal object WorkflowRunsChecks : RunRows("workflow_runs_checks") {
    val nodeId = text("node_id")
    val attestation = text("attestation")
}

/** The notifications a session's actions sent. */
internal object WorkflowRunsNotifications : RunRows("workflow_runs_notifications") {
    val notifyId = text("notify_id")
    val status = text("status")
    val actionKey = text("action_key")
}
