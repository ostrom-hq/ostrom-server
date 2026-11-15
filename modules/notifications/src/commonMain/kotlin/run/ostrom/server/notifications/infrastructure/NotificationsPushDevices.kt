// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The members' phones of [NotificationsMigrations] versions 1 to 3, as Exposed reads and writes them. The migrations
 * create the schema; this object only names its columns and their types.
 */
internal object NotificationsPushDevices : Table("notifications_push_devices") {
    val orgId = uuid("org_id")
    val id = uuid("id")
    val memberId = uuid("member_id")
    val pushToken = text("push_token")
    val provider = text("provider")
    val platform = text("platform")
    val locale = text("locale").nullable()
    val createdAt = instant("created_at")
    val seenAt = instant("seen_at")

    // Version 2: the person and the credential that registered the phone, and whether it was a browser session.
    val userId = uuid("user_id").nullable()
    val credentialId = uuid("credential_id").nullable()
    val browser = bool("browser")

    // Version 3: the phone's key that seals the pages it receives.
    val publicKey = text("public_key").nullable()

    override val primaryKey = PrimaryKey(orgId, id)
}
