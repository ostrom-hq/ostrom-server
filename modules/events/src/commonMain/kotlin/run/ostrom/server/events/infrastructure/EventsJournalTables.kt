// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * The organization's cursor of [EventsMigrations] version 1, as the Exposed publisher moves it: its row lock is the one
 * every publisher of an organization takes, on either pool. The migrations create the schema; this object only names
 * its columns.
 */
internal object EventsCursors : Table("events_cursors") {
    val orgId = uuid("org_id")
    val lastSeq = long("last_seq")

    override val primaryKey = PrimaryKey(orgId)
}

/** The journal of [EventsMigrations] version 1, as the Exposed publisher appends to it. */
internal object EventsJournal : Table("events_journal") {
    val orgId = uuid("org_id")
    val seq = long("seq")
    val type = text("type")
    val subject = text("subject")
    val subjectId = text("subject_id")
    val memberId = uuid("member_id").nullable()
    val at = instant("at")
    val data = text("data")

    override val primaryKey = PrimaryKey(orgId, seq)
}

/** The organization's head: the last sequence its publishers committed, 0 before its first event. */
internal fun JdbcTransaction.headOf(orgId: Uuid): Long {
    val c = EventsCursors
    val last = c.lastSeq.max()
    return c.select(last).where { c.orgId eq orgId }.single()[last] ?: 0
}

/** A row of [EventsJournal], every column read but its organization's. */
internal fun ResultRow.toJournalEvent(): JournalEvent {
    val j = EventsJournal
    return JournalEvent(
        seq = this[j.seq],
        type = this[j.type],
        subject = this[j.subject],
        subjectId = this[j.subjectId],
        memberId = this[j.memberId],
        at = this[j.at],
        data = this[j.data],
    )
}
