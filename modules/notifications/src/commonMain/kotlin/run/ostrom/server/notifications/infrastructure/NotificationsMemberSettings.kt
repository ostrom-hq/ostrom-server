// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import org.jetbrains.exposed.v1.core.Table

/**
 * The members' notification switches of [NotificationsMigrations] version 4, as Exposed reads and writes them. The
 * migrations create the schema; this object only names its columns and their types.
 */
internal object NotificationsMemberSettings : Table("notifications_member_settings") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val channel = text("channel")
    val kind = text("kind")
    val enabled = bool("enabled")

    override val primaryKey = PrimaryKey(orgId, memberId, channel, kind)
}
