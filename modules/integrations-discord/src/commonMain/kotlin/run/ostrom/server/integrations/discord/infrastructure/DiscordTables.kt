// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.infrastructure

import org.jetbrains.exposed.v1.core.Table
import run.ostrom.server.kernel.database.instant

// The tables of [DiscordMigrations], as Exposed reads and writes them: the migrations create the schema, these objects
// only name its columns. All three are an organization's, under row-level security.

/** Each member's Discord link, one per member, a Discord account linked by one member only. */
internal object DiscordLinks : Table("discord_links") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val discordUserId = text("discord_user_id")
    val username = text("discord_username")
    val dmChannelId = text("dm_channel_id").nullable()
    val linkedAt = instant("linked_at")
    override val primaryKey = PrimaryKey(orgId, memberId)
}

/** The unfinished links, by the hash of their state, bound to the browser session that started them. */
internal object DiscordLinkAttempts : Table("discord_link_attempts") {
    val orgId = uuid("org_id")
    val stateHash = text("state_hash")
    val memberId = uuid("member_id")
    val binding = text("binding")
    val expiresAt = instant("expires_at")
    override val primaryKey = PrimaryKey(orgId, stateHash)
}

/** The yes requests' messages in the approvers' Discord, one per request. */
internal object DiscordApprovalMessages : Table("discord_approval_messages") {
    val orgId = uuid("org_id")
    val approvalId = uuid("approval_id")
    val gateId = uuid("gate_id")
    val channelId = text("channel_id")
    val messageId = text("message_id")
    override val primaryKey = PrimaryKey(orgId, approvalId)
}
