// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.update
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.events.JournalEntry
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.JournalSlice
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.kernel.events.OrgEventType

/**
 * [OrgEventJournal] on PostgreSQL (versions 3 and 4 of [EventsMigrations]): the readers' cursors in
 * `events_readers`, found across organizations only through `events_readers_behind`, read and moved inside each
 * organization, in a sqlx4k or an Exposed transaction of the reader's.
 *
 * @param driver The database.
 */
class OrgEventJournalImpl(private val driver: Driver) : OrgEventJournal {
    override suspend fun behind(reader: String, limit: Int): List<JournalPosition> {
        require(READER.matches(reader)) { "A reader is named [a-z][a-z0-9-]*, at most 32 characters." }
        return driver.fetchAll(
            Statement.create(BEHIND_SQL).bind("reader", reader).bind("limit", limit),
        ).getOrThrow().map {
            JournalPosition(it.get("org_id").asUuid(), it.get("from_seq").asLong(), it.get("head").asLong())
        }
    }

    override suspend fun read(position: JournalPosition, types: Set<OrgEventType>, limit: Int): JournalSlice {
        val orgId = position.orgId
        val rows = driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create(READ_SQL)
                    .bind("org_id", orgId)
                    .bind("after", position.from)
                    .bind("head", position.head)
                    .bind("types", types.map { it.wire }.sorted().joinToString(","))
                    .bind("limit", limit + 1),
            ).getOrThrow().map { row ->
                val wire = row.get("type").asString()
                JournalEntry(
                    seq = row.get("seq").asLong(),
                    type = types.first { it.wire == wire },
                    subjectId = row.get("subject_id").asString(),
                    memberId = row.get("member_id").asUuidOrNull(),
                    at = row.get("at").asInstant(),
                    data = Json.parseToJsonElement(row.get("data").asString()).jsonObject,
                )
            }
        }
        val page = rows.take(limit)
        // A full page stops at its last event; otherwise everything up to the head was read.
        return JournalSlice(page, if (rows.size > limit) page.last().seq else position.head)
    }

    override suspend fun advanceIn(
        transaction: Transaction,
        reader: String,
        position: JournalPosition,
        through: Long,
    ): Boolean = transaction.fetchAll(
        Statement.create(ADVANCE_SQL)
            .bind("org_id", position.orgId)
            .bind("reader", reader)
            .bind("from", position.from)
            .bind("through", through),
    ).getOrThrow().any()

    // The Exposed DSL runs in the caller's current transaction, which is [transaction].
    override fun advanceIn(
        transaction: JdbcTransaction,
        reader: String,
        position: JournalPosition,
        through: Long,
    ): Boolean {
        require(READER.matches(reader)) { "A reader is named [a-z][a-z0-9-]*, at most 32 characters." }
        return EventsReaders.update({
            (EventsReaders.orgId eq position.orgId) and (EventsReaders.reader eq reader) and
                (EventsReaders.seq eq position.from)
        }) { it[EventsReaders.seq] = through } > 0
    }

    private companion object {
        private val READER = Regex("[a-z][a-z0-9-]{0,31}")
        const val BEHIND_SQL =
            "SELECT org_id, from_seq, head FROM events_readers_behind(CAST(:reader AS TEXT), CAST(:limit AS INTEGER))"
        const val READ_SQL =
            "SELECT seq, type, subject_id, member_id, at, data FROM events_journal " +
                "WHERE org_id = :org_id AND seq > :after AND seq <= :head " +
                "AND type = ANY(string_to_array(CAST(:types AS TEXT), ',')) ORDER BY seq LIMIT :limit"
        const val ADVANCE_SQL =
            "UPDATE events_readers SET seq = :through " +
                "WHERE org_id = :org_id AND reader = :reader AND seq = :from RETURNING seq"
    }
}
