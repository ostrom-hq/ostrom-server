// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [SlackMigrations], as Exposed reads and writes them: the migrations create the schema, these objects
// only name its columns. Both are an organization's, under row-level security.

/** Each member's Slack link, one per member, a Slack account linked by one member only. */
internal object SlackLinks : Table("slack_links") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val slackUserId = text("slack_user_id")
    val linkedAt = instant("linked_at")
    override val primaryKey = PrimaryKey(orgId, memberId)
}

/** The yes requests' messages in the approvers' Slack, one per request. */
internal object SlackApprovalMessages : Table("slack_approval_messages") {
    val orgId = uuid("org_id")
    val approvalId = uuid("approval_id")
    val gateId = uuid("gate_id")
    val slackChannel = text("slack_channel")
    val ts = text("ts")
    override val primaryKey = PrimaryKey(orgId, approvalId)
}
