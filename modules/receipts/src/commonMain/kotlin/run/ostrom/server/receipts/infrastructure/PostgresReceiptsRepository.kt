// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts.infrastructure

import run.ostrom.server.receipts.domain.models.ReceiptQuery
import run.ostrom.server.receipts.domain.models.StoredReceipt
import run.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * [ReceiptsRepository] on PostgreSQL (tables of [ReceiptsMigrations]). Every query runs in a transaction scoped to
 * one organization by `ostrom.org_id`, which row level security enforces.
 *
 * @param driver The connection pool.
 */
class PostgresReceiptsRepository(private val driver: Driver) : ReceiptsRepository {
    override suspend fun insertIfAbsent(receipt: StoredReceipt): StoredReceipt = driver.transaction {
        scope(receipt.orgId)
        execute(
            Statement.create(
                "INSERT INTO receipts_receipts (org_id, id, subject_kind, subject_id, body, created_at) " +
                    "VALUES (:org, :id, :kind, :subject, :body, :created_at) ON CONFLICT (org_id, id) DO NOTHING",
            )
                .bind("org", receipt.orgId)
                .bind("id", receipt.id)
                .bind("kind", receipt.subjectKind)
                .bind("subject", receipt.subjectId)
                .bind("body", Json.encodeToString(JsonObject.serializer(), receipt.body))
                .bind("created_at", receipt.createdAt),
        ).getOrThrow()
        // The row exists now: ours, or the one another signer stored first under the same id.
        select(receipt.orgId, receipt.id).single()
    }

    override suspend fun get(orgId: Uuid, id: String): StoredReceipt? = driver.transaction {
        scope(orgId)
        select(orgId, id).firstOrNull()
    }

    override suspend fun list(orgId: Uuid, query: ReceiptQuery): List<StoredReceipt> = driver.transaction {
        scope(orgId)
        val filters = buildString {
            append("org_id = :org")
            if (query.subjectKind != null) append(" AND subject_kind = :kind")
            if (query.subjectId != null) append(" AND subject_id = :subject")
            if (query.before != null) {
                append(
                    " AND (created_at, id) < (SELECT created_at, id FROM receipts_receipts " +
                        "WHERE org_id = :org AND id = :before)",
                )
            }
        }
        val statement = Statement.create(
            "SELECT $COLUMNS FROM receipts_receipts WHERE $filters ORDER BY created_at DESC, id DESC LIMIT :limit",
        ).bind("org", orgId).bind("limit", query.limit)
        // A named parameter is bound only when the SQL names it: sqlx4k refuses an unknown one.
        query.subjectKind?.let { statement.bind("kind", it) }
        query.subjectId?.let { statement.bind("subject", it) }
        query.before?.let { statement.bind("before", it) }
        fetchAll(statement).getOrThrow().map { it.toReceipt() }
    }

    private suspend fun QueryExecutor.scope(orgId: Uuid) {
        fetchAll(
            Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId.toString()),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.select(orgId: Uuid, id: String): List<StoredReceipt> = fetchAll(
        Statement.create("SELECT $COLUMNS FROM receipts_receipts WHERE org_id = :org AND id = :id")
            .bind("org", orgId)
            .bind("id", id),
    ).getOrThrow().map { it.toReceipt() }

    private fun ResultSet.Row.toReceipt() = StoredReceipt(
        orgId = get("org_id").asUuid(),
        id = get("id").asString(),
        subjectKind = get("subject_kind").asString(),
        subjectId = get("subject_id").asString(),
        body = Json.parseToJsonElement(get("body").asString()).jsonObject,
        createdAt = get("created_at").asInstant(),
    )

    private companion object {
        const val COLUMNS = "org_id, id, subject_kind, subject_id, body, created_at"
    }
}
