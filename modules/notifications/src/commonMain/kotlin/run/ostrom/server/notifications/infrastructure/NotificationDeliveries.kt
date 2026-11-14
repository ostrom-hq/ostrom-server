// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The router's delivery queue of [NotificationsMigrations] version 6, as Exposed reads and writes it. The migrations
 * create the schema; this object only names its columns and their types.
 */
internal object NotificationDeliveries : Table("notifications_deliveries") {
    val orgId = uuid("org_id")
    val id = uuid("id")
    val eventSeq = long("event_seq")
    val memberId = uuid("member_id")
    val channel = text("channel")
    val kind = text("kind")
    val title = text("title")
    val body = text("body")
    val sessionId = text("session_id").nullable()
    val waitId = text("wait_id").nullable()
    val collapseKey = text("collapse_key")
    val state = text("state")
    val attempts = integer("attempts")
    val nextAttemptAt = instant("next_attempt_at")
    val leaseUntil = instant("lease_until")
    val lastError = text("last_error").nullable()
    val createdAt = instant("created_at")
    val deliveredAt = instant("delivered_at").nullable()

    override val primaryKey = PrimaryKey(orgId, id)
}
