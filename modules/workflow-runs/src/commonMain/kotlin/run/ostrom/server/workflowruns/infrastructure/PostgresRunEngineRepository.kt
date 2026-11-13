// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.workflowruns.domain.models.PlayedRun
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.models.RunRef
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RunEngineRepository] on PostgreSQL: the lease crosses organizations through `workflow_runs_claim_due` only; a run
 * is read, changed and released in a transaction scoped to its organization by `ostrom.org_id`.
 *
 * @param driver The connection pool.
 * @param audit The organization's signed audit log, written in the transaction that changes a run.
 */
class PostgresRunEngineRepository(
    private val driver: Driver,
    private val audit: TransactionalAuditLog,
) : RunEngineRepository {
    override suspend fun claimDue(owner: String, now: Instant, until: Instant, limit: Int): List<RunRef> =
        driver.fetchAll(
            Statement.create(EngineQueries.CLAIM).bind("owner", owner).bind("until", until).bind("now", now)
                .bind("limit", limit),
        ).getOrThrow().map { RunRef(it.get("org_id").asUuid(), it.get("run_id").asString()) }

    override suspend fun played(orgId: Uuid, id: String): PlayedRun? = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(Statement.create(EngineQueries.PLAYED).bind("org_id", orgId).bind("id", id)).getOrThrow()
            .firstOrNull()?.let { PlayedRun(it.run(), it.graph()) }
    }

    override suspend fun write(orgId: Uuid, change: RunChange): Boolean = driver.withOrganization(orgId) { tx ->
        val row = tx.fetchAll(Statement.create(EngineQueries.LOCK).bind("org_id", orgId).bind("id", change.next.id))
            .getOrThrow().firstOrNull()
        val holds = row != null && row.run() == change.expected &&
            (change.owner == null || row.get("lease_owner").asStringOrNull() == change.owner)
        val written = holds && begun(tx, orgId, change)
        if (written) apply(tx, orgId, change)
        written
    }

    override suspend fun release(orgId: Uuid, id: String, owner: String, nextAt: Instant?) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(EngineQueries.RELEASE).bind("org_id", orgId).bind("id", id).bind("owner", owner)
                    .instant("next_at", nextAt),
            ).getOrThrow()
        }
    }

    // The visit's key, inserted once: a second insert finds it and the change is dropped.
    private suspend fun begun(tx: Transaction, orgId: Uuid, change: RunChange): Boolean {
        val visit = change.visit ?: return true
        return tx.fetchAll(
            Statement.create(EngineQueries.BEGIN).bind("org_id", orgId).bind("run_id", change.next.id)
                .bind("seq", visit.seq).bind("node_id", visit.nodeId).bind("at", Instant.parse(visit.startedAt)),
        ).getOrThrow().any()
    }

    private suspend fun apply(tx: Transaction, orgId: Uuid, change: RunChange) {
        val run = change.next
        val dueAt = change.dueAt
        val statement = Statement.create(if (dueAt == null) EngineQueries.UPDATE else EngineQueries.UPDATE_DUE)
            .bind("org_id", orgId).bind("id", run.id).bind("status", run.status.value)
            .bind("body", OstromJson.encodeToString(WorkflowRun.serializer(), run))
        tx.execute(if (dueAt == null) statement else statement.bind("due_at", dueAt)).getOrThrow()
        change.audit.forEach { audit.recordRun(tx, orgId, run.id, it) }
    }

    private fun ResultSet.Row.run(): WorkflowRun =
        OstromJson.decodeFromString(WorkflowRun.serializer(), get("body").asString())

    // A run started before the engine copied no graph: an empty one, which fails it with nothing to play.
    private fun ResultSet.Row.graph(): RunGraph {
        val raw = get("graph").asStringOrNull()
        return if (raw == null) RunGraph() else OstromJson.decodeFromString(RunGraph.serializer(), raw)
    }

    private fun Statement.instant(name: String, value: Instant?): Statement =
        if (value == null) bindNull(name, Instant::class) else bind(name, value)
}

/** The engine's SQL. */
internal object EngineQueries {
    /** The runs due now, leased to a replica, across organizations. */
    const val CLAIM: String =
        "SELECT run_id, org_id FROM workflow_runs_claim_due(CAST(:owner AS TEXT), CAST(:until AS TIMESTAMPTZ), " +
            "CAST(:now AS TIMESTAMPTZ), CAST(:limit AS INTEGER))"

    /** A run and its graph. */
    const val PLAYED: String =
        "SELECT body, CAST(graph AS TEXT) AS graph FROM workflow_runs_started WHERE org_id = :org_id AND id = :id"

    /** A run locked for its change. */
    const val LOCK: String =
        "SELECT body, lease_owner FROM workflow_runs_started WHERE org_id = :org_id AND id = :id FOR UPDATE"

    /** A visit's key. */
    const val BEGIN: String =
        "INSERT INTO workflow_runs_visits (org_id, run_id, seq, node_id, at) VALUES (:org_id, :run_id, :seq, " +
            ":node_id, :at) ON CONFLICT DO NOTHING RETURNING seq"

    /** A run's change. */
    const val UPDATE: String =
        "UPDATE workflow_runs_started SET status = :status, body = CAST(:body AS JSONB) " +
            "WHERE org_id = :org_id AND id = :id"

    /** A person's change of a run, which the engine looks at again at the given time. */
    const val UPDATE_DUE: String =
        "UPDATE workflow_runs_started SET status = :status, body = CAST(:body AS JSONB), next_step_at = :due_at " +
            "WHERE org_id = :org_id AND id = :id"

    /** A lease ended. */
    const val RELEASE: String =
        "UPDATE workflow_runs_started SET lease_owner = NULL, lease_until = NULL, next_step_at = :next_at " +
            "WHERE org_id = :org_id AND id = :id AND lease_owner = :owner"
}
