// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The instance's identities at the push relays ([NotificationsMigrations] version 5), as Exposed reads and writes
 * them: the migrations create the schema, this object only names its columns.
 */
internal object PushRelayInstances : Table("notifications_push_relay_instances") {
    val relayUrl = text("relay_url")
    val publicKey = text("public_key")

    /** Sealed with the secrets key (context `push-relay:<url>`). */
    val privateKey = text("private_key")
    val instanceId = text("instance_id").nullable()
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(relayUrl)
}
