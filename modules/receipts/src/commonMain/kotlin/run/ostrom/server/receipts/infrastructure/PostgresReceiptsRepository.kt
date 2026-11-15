// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.rowBefore
import run.ostrom.server.kernel.database.write
import run.ostrom.server.receipts.domain.models.ReceiptQuery
import run.ostrom.server.receipts.domain.models.StoredReceipt
import run.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryAlias
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.unionAll
import kotlin.uuid.Uuid

/**
 * [ReceiptsRepository] on `receipts_receipts` (tables of [ReceiptsMigrations]): every statement filters on its
 * organization and runs in it (`inOrg`), under row-level security; the tenant role may only read and insert
 * (migration 4), and the append-only trigger refuses any change to every role.
 *
 * @param database The serving role's pool.
 */
class PostgresReceiptsRepository(private val database: JdbcDatabase) : ReceiptsRepository {
    override suspend fun insertIfAbsent(receipt: StoredReceipt): StoredReceipt = database.inOrg(receipt.orgId) {
        write { receiptInsert(receipt) }
        // The row exists now: ours, or the one another signer stored first under the same id.
        receipt(receipt.orgId, receipt.id).single()
    }

    override suspend fun get(orgId: Uuid, id: String): StoredReceipt? = database.inOrg(orgId) {
        receipt(orgId, id).singleOrNull()
    }

    override suspend fun list(orgId: Uuid, query: ReceiptQuery): List<StoredReceipt> = database.inOrg(orgId) {
        receiptsPage(orgId, query).rows()
    }
}

private val R = ReceiptsReceipts

/** Stores [receipt] unless its organization has one under its id: `INSERT … ON CONFLICT DO NOTHING`. */
internal fun StatementBuilder.receiptInsert(receipt: StoredReceipt): InsertStatement<Long> = R.insertIgnore {
    it[orgId] = receipt.orgId
    it[id] = receipt.id
    it[subjectKind] = receipt.subjectKind
    it[subjectId] = receipt.subjectId
    it[body] = Json.encodeToString(JsonObject.serializer(), receipt.body)
    it[createdAt] = receipt.createdAt
    it[sessionId] = receipt.sessionId
    it[member] = receipt.member
}

/** The receipt [id] of [orgId], by the primary key. */
internal fun receipt(orgId: Uuid, id: String): Read<StoredReceipt> =
    Read(R.select(R.columns).where { (R.orgId eq orgId) and (R.id eq id) }) { it.toReceipt(null) }

/**
 * A page of receipts, newest first, at most [ReceiptQuery.limit]. For a partial reader ([ReceiptQuery.audience],
 * TeamCode #238), the page is the newest of three pages, each read through its own index and joined by `UNION ALL`:
 * the receipts about no session, those of the reader's own sessions (when their login is known), and those of the
 * sessions whose gates they approve (one array parameter, however many), the last without the reader's own so that
 * none is read twice.
 *
 * @param orgId The organization.
 * @param query Its filters.
 *
 * @return The read.
 */
internal fun receiptsPage(orgId: Uuid, query: ReceiptQuery): Read<StoredReceipt> {
    val filters = buildList {
        add(R.orgId eq orgId)
        query.subjectKind?.let { add(R.subjectKind eq it) }
        query.subjectId?.let { add(R.subjectId eq it) }
        query.before?.let { before ->
            val cursor = R.select(R.createdAt, R.id).where { (R.orgId eq orgId) and (R.id eq before) }
            add(rowBefore(listOf(R.createdAt, R.id), cursor))
        }
    }
    val audience = query.audience ?: return Read(page(filters, query.limit)) { it.toReceipt(null) }
    val member = audience.member
    val approved = R.sessionId eq anyFrom(audience.sessions)
    val branches = listOfNotNull(
        R.sessionId.isNull(),
        member?.let { R.member eq it },
        if (member != null) approved and (R.member.isNull() or (R.member neq member)) else approved,
    ).map { page(filters + it, query.limit) }
    val union = branches.drop(1).fold<Query, AbstractQuery<*>>(branches.first()) { all, next -> all.unionAll(next) }
    val readable = union.alias("readable")
    val newest = readable.select(readable.columns)
        .orderBy(readable[R.createdAt] to SortOrder.DESC, readable[R.id] to SortOrder.DESC)
        .limit(query.limit)
    return Read(newest) { it.toReceipt(readable) }
}

private fun page(filters: List<Op<Boolean>>, limit: Int): Query = R.select(R.columns)
    .where { filters.reduce { all, next -> all and next } }
    .orderBy(R.createdAt to SortOrder.DESC, R.id to SortOrder.DESC)
    .limit(limit)

/** The receipt of this row, read from the table or, when set, from [alias] of a query on it. */
private fun ResultRow.toReceipt(alias: QueryAlias?): StoredReceipt {
    fun <T> col(column: Column<T>): T = this[if (alias == null) column else alias[column]]
    return StoredReceipt(
        orgId = col(R.orgId),
        id = col(R.id),
        subjectKind = col(R.subjectKind),
        subjectId = col(R.subjectId),
        body = Json.parseToJsonElement(col(R.body)).jsonObject,
        createdAt = col(R.createdAt),
        sessionId = col(R.sessionId),
        member = col(R.member),
    )
}
