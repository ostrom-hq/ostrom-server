// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

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
