// SPDX-License-Identifier: Apache-2.0
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
                "INSERT INTO receipts_receipts " +
                    "(org_id, id, subject_kind, subject_id, body, created_at, session_id, member) " +
                    "VALUES (:org, :id, :kind, :subject, :body, :created_at, :session, :member) " +
                    "ON CONFLICT (org_id, id) DO NOTHING",
            )
                .bind("org", receipt.orgId)
                .bind("id", receipt.id)
                .bind("kind", receipt.subjectKind)
                .bind("subject", receipt.subjectId)
                .bind("body", Json.encodeToString(JsonObject.serializer(), receipt.body))
                .bind("created_at", receipt.createdAt)
                .nullable("session", receipt.sessionId)
                .nullable("member", receipt.member),
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
        val audience = query.audience
        val statement = Statement.create(ReceiptsQueries.list(query)).bind("org", orgId).bind("limit", query.limit)
        // A named parameter is bound only when the SQL names it: sqlx4k refuses an unknown one.
        query.subjectKind?.let { statement.bind("kind", it) }
        query.subjectId?.let { statement.bind("subject", it) }
        query.before?.let { statement.bind("before", it) }
        audience?.member?.let { statement.bind("member", it) }
        audience?.let { statement.bind("sessions", Json.encodeToString(sessionsSerializer, it.sessions)) }
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
        sessionId = get("session_id").asStringOrNull(),
        member = get("member").asStringOrNull(),
    )

    private fun Statement.nullable(name: String, value: String?): Statement =
        if (value == null) bindNull(name, String::class) else bind(name, value)
}

private const val COLUMNS = "org_id, id, subject_kind, subject_id, body, created_at, session_id, member"
private const val CURSOR =
    "(created_at, id) < (SELECT created_at, id FROM receipts_receipts WHERE org_id = :org AND id = :before)"
private val sessionsSerializer = ListSerializer(String.serializer())

/** The SQL of the repository, also read by the query plans test. */
object ReceiptsQueries {
    /**
     * A page of receipts, newest first. For a partial reader ([ReceiptQuery.audience], TeamCode #238), the page is
     * the newest of three pages, each read through its own index: the receipts about no session, those of the reader's
     * own sessions (`:member`, when known), and those of the sessions whose gates they approve (`:sessions`, a JSON
     * array of texts), the last without the reader's own so that none is read twice.
     *
     * @param query Which filters are bound.
     * @return The SQL.
     */
    fun list(query: ReceiptQuery): String {
        val filters = buildList {
            add("org_id = :org")
            if (query.subjectKind != null) add("subject_kind = :kind")
            if (query.subjectId != null) add("subject_id = :subject")
            if (query.before != null) add(CURSOR)
        }
        val audience = query.audience ?: return page(filters)
        val approved = "session_id IN (SELECT jsonb_array_elements_text(CAST(:sessions AS JSONB)))"
        val branches = listOfNotNull(
            "session_id IS NULL",
            "member = :member".takeIf { audience.member != null },
            if (audience.member != null) "$approved AND (member IS NULL OR member <> :member)" else approved,
        )
        val union = branches.joinToString(" UNION ALL ") { "(${page(filters + it)})" }
        return "SELECT $COLUMNS FROM ($union) AS readable ORDER BY created_at DESC, id DESC LIMIT :limit"
    }

    private fun page(filters: List<String>): String =
        "SELECT $COLUMNS FROM receipts_receipts WHERE ${filters.joinToString(" AND ")} " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit"
}
