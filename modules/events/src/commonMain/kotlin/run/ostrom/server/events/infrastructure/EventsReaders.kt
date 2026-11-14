// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import org.jetbrains.exposed.v1.core.Table

/**
 * The readers' cursors of [EventsMigrations] version 3, as Exposed moves them for a reader on Exposed. The migrations
 * create the schema; this object only names its columns.
 */
internal object EventsReaders : Table("events_readers") {
    val orgId = uuid("org_id")
    val reader = text("reader")
    val seq = long("seq")

    override val primaryKey = PrimaryKey(orgId, reader)
}
