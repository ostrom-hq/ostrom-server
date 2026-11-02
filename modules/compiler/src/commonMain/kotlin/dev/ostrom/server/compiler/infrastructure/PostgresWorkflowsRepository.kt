// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.infrastructure

import dev.ostrom.server.compiler.domain.models.Authorship
import dev.ostrom.server.compiler.domain.models.DecisionDoc
import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import dev.ostrom.server.compiler.domain.repositories.WorkflowsRepository
import dev.ostrom.server.compiler.domain.services.CompilerDocs
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * [WorkflowsRepository] on PostgreSQL (sqlx4k): the organization's workflows and the decisions on them, one
 * transaction per call under the organization's row level security.
 *
 * @param driver The connection pool.
 */
class PostgresWorkflowsRepository(private val driver: Driver) : WorkflowsRepository {
    private suspend fun <T> scoped(orgId: Uuid, block: suspend CompilerTx.() -> T): T = driver.inOrg(orgId, block)

    override suspend fun workflows(orgId: Uuid): List<StoredWorkflow> = scoped(orgId) {
        fetch(
            Statement.create(
                "SELECT $WORKFLOW_COLUMNS FROM compiler_workflows WHERE org_id = :org " +
                    "ORDER BY created_at, id LIMIT $MAX_ROWS",
            ).bind("org", orgId),
        ).map { it.toWorkflow() }
    }

    override suspend fun workflow(orgId: Uuid, id: String): StoredWorkflow? = scoped(orgId) {
        fetch(
            Statement.create("SELECT $WORKFLOW_COLUMNS FROM compiler_workflows WHERE org_id = :org AND id = :id")
                .bind("org", orgId).bind("id", id),
        ).firstOrNull()?.toWorkflow()
    }

    override suspend fun saveWorkflow(
        orgId: Uuid,
        id: String,
        raw: JsonObject,
        expectedVersion: Int?,
        author: Authorship,
    ): StoredWorkflow? = scoped(orgId) {
        val statement = if (expectedVersion == null) {
            Statement.create(
                "INSERT INTO compiler_workflows (org_id, id, doc, version, updated_by, updated_at, created_at) " +
                    "VALUES (:org, :id, :doc, 1, :by, :at, :at) ON CONFLICT (org_id, id) DO NOTHING " +
                    "RETURNING $WORKFLOW_COLUMNS",
            )
        } else {
            Statement.create(
                "UPDATE compiler_workflows SET doc = :doc, version = version + 1, updated_by = :by, " +
                    "updated_at = :at WHERE org_id = :org AND id = :id AND version = :expected " +
                    "RETURNING $WORKFLOW_COLUMNS",
            ).bind("expected", expectedVersion)
        }
        statement.bind("org", orgId).bind("id", id).bind("doc", raw.toString())
            .bind("by", author.by).bind("at", author.at)
        fetch(statement).firstOrNull()?.toWorkflow()
    }

    override suspend fun deleteWorkflow(orgId: Uuid, id: String): Boolean = scoped(orgId) {
        execute(
            Statement.create("DELETE FROM compiler_workflows WHERE org_id = :org AND id = :id")
                .bind("org", orgId).bind("id", id),
        ) > 0
    }

    override suspend fun decisions(orgId: Uuid): List<DecisionDoc> = scoped(orgId) {
        fetch(
            Statement.create(
                "SELECT id, subject_kind, subject_id, decided_by, decided_at, receipt_id FROM compiler_decisions " +
                    "WHERE org_id = :org ORDER BY decided_at, id LIMIT $MAX_ROWS",
            ).bind("org", orgId),
        ).map {
            DecisionDoc(
                id = it.get("id").asString(),
                subjectKind = it.get("subject_kind").asString(),
                subjectId = it.get("subject_id").asString(),
                by = it.get("decided_by").asString(),
                at = it.get("decided_at").asString(),
                receiptId = it.get("receipt_id").asStringOrNull(),
            )
        }
    }

    override suspend fun addDecision(orgId: Uuid, decision: DecisionDoc) {
        scoped(orgId) {
            execute(
                Statement.create(
                    "INSERT INTO compiler_decisions (org_id, id, subject_kind, subject_id, decided_by, decided_at, " +
                        "receipt_id) VALUES (:org, :id, :kind, :subject, :by, :at, :receipt)",
                ).bind("org", orgId).bind("id", decision.id).bind("kind", decision.subjectKind)
                    .bind("subject", decision.subjectId).bind("by", decision.by).bind("at", decision.at)
                    .bind("receipt", decision.receiptId),
            )
        }
    }

    private companion object {
        const val MAX_ROWS = 1000
        const val WORKFLOW_COLUMNS = "doc, version, updated_by, updated_at"

        fun ResultSet.Row.toWorkflow() = StoredWorkflow(
            doc = CompilerDocs.workflow(json.parseToJsonElement(get("doc").asString()).jsonObject),
            version = get("version").asInt(),
            updatedBy = get("updated_by").asString(),
            updatedAt = get("updated_at").asInstant(),
        )
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val json = Json { ignoreUnknownKeys = true }
