// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.events.JournalEntry
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.JournalSlice
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.kernel.events.OrgEventType

/**
 * [OrgEventJournal] on PostgreSQL (versions 3 and 4 of [EventsMigrations]): the readers' cursors in
 * `events_readers`, found across organizations only through `events_readers_behind`, read and moved inside each
 * organization, in a transaction of the reader's.
 *
 * @param database The serving role's pool.
 */
class OrgEventJournalImpl(private val database: JdbcDatabase) : OrgEventJournal {
    override suspend fun behind(reader: String, limit: Int): List<JournalPosition> {
        require(READER.matches(reader)) { "A reader is named [a-z][a-z0-9-]*, at most 32 characters." }
        return database.query {
            val b = EventsReadersBehind
            b.of(reader, limit).select(b.orgId, b.fromSeq, b.head).map {
                JournalPosition(it[b.orgId], it[b.fromSeq], it[b.head])
            }
        }
    }

    override suspend fun read(position: JournalPosition, types: Set<OrgEventType>, limit: Int): JournalSlice {
        val orgId = position.orgId
        val rows = database.withOrganization(orgId) {
            val j = EventsJournal
            j.select(j.seq, j.type, j.subjectId, j.memberId, j.at, j.data)
                .where {
                    (j.orgId eq orgId) and (j.seq greater position.from) and (j.seq lessEq position.head) and
                        (j.type inList types.map { it.wire }.sorted())
                }
                .orderBy(j.seq to SortOrder.ASC)
                .limit(limit + 1)
                .map { row ->
                    val wire = row[j.type]
                    JournalEntry(
                        seq = row[j.seq],
                        type = types.first { it.wire == wire },
                        subjectId = row[j.subjectId],
                        memberId = row[j.memberId],
                        at = row[j.at],
                        data = Json.parseToJsonElement(row[j.data]).jsonObject,
                    )
                }
        }
        val page = rows.take(limit)
        // A full page stops at its last event; otherwise everything up to the head was read.
        return JournalSlice(page, if (rows.size > limit) page.last().seq else position.head)
    }

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
    }
}
