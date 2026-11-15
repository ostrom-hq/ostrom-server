// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/*
 * The webhooks of [EventsMigrations] version 2, as Exposed reads and writes them. The migrations create the schema;
 * these objects only name its columns. Both are tenant tables, read and written by the serving role with the
 * organization set (`withOrganization`), each query filtered on `org_id` too.
 */

/** An organization's webhooks. */
internal object EventsWebhooks : Table("events_webhooks") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val url = text("url")
    val types = text("types")
    val description = text("description")
    val enabled = bool("enabled")
    val disabledReason = text("disabled_reason").nullable()
    val secret = text("secret")
    val createdBy = uuid("created_by")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")
    val version = integer("version")
    val fanoutSeq = long("fanout_seq")
    val failures = integer("failures")

    override val primaryKey = PrimaryKey(id)
}

/** The deliveries of a webhook, one per event (`UNIQUE (webhook_id, event_seq)`), tests without one. */
internal object EventsWebhookDeliveries : Table("events_webhook_deliveries") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val webhookId = uuid("webhook_id")
    val eventSeq = long("event_seq").nullable()
    val eventType = text("event_type")
    val payload = text("payload")
    val state = text("state")
    val attempts = integer("attempts")
    val nextAttemptAt = instant("next_attempt_at")
    val leaseUntil = instant("lease_until")
    val lastStatus = integer("last_status").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = instant("created_at")
    val deliveredAt = instant("delivered_at").nullable()
    val test = bool("test")

    override val primaryKey = PrimaryKey(id)
}
