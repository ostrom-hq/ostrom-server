// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessage
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessagesRepository
import run.ostrom.server.integrations.slack.domain.SlackLinksRepository
import run.ostrom.server.integrations.slack.domain.SlackPosted
import run.ostrom.server.integrations.slack.domain.StoredSlackLink
import run.ostrom.server.kernel.database.withOrganization
import kotlin.uuid.Uuid

/**
 * [SlackLinksRepository] on `slack_links` (version 1 of [SlackMigrations]), every statement confined to its
 * organization.
 *
 * @param driver The database.
 */
class PostgresSlackLinksRepository(private val driver: Driver) : SlackLinksRepository {
    override suspend fun of(orgId: Uuid, memberId: Uuid): StoredSlackLink? = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(Statement.create(OF_SQL).bind("org_id", orgId).bind("member_id", memberId))
            .getOrThrow().firstOrNull()?.toLink()
    }

    override suspend fun ofMembers(orgId: Uuid, memberIds: Set<Uuid>): List<StoredSlackLink> {
        if (memberIds.isEmpty()) return emptyList()
        return driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create(OF_MEMBERS_SQL).bind("org_id", orgId).bind("members", memberIds.joinToString(",")),
            ).getOrThrow().map { it.toLink() }
        }
    }

    override suspend fun memberOf(orgId: Uuid, slackUserId: String): Uuid? = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(Statement.create(MEMBER_OF_SQL).bind("org_id", orgId).bind("slack_user_id", slackUserId))
            .getOrThrow().firstOrNull()?.let { it.get("member_id").asUuid() }
    }

    override suspend fun save(orgId: Uuid, link: StoredSlackLink) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(SAVE_SQL).bind("org_id", orgId).bind("member_id", link.memberId)
                    .bind("slack_user_id", link.slackUserId).bind("linked_at", link.linkedAt),
            ).getOrThrow()
        }
    }

    override suspend fun remove(orgId: Uuid, memberId: Uuid) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(Statement.create(REMOVE_SQL).bind("org_id", orgId).bind("member_id", memberId)).getOrThrow()
        }
    }

    override suspend fun deleteAll(orgId: Uuid) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(Statement.create(DELETE_SQL).bind("org_id", orgId)).getOrThrow()
        }
    }

    private fun ResultSet.Row.toLink() = StoredSlackLink(
        memberId = get("member_id").asUuid(),
        slackUserId = get("slack_user_id").asString(),
        linkedAt = get("linked_at").asInstant(),
    )

    private companion object {
        const val COLUMNS = "member_id, slack_user_id, linked_at"
        const val OF_SQL = "SELECT $COLUMNS FROM slack_links WHERE org_id = :org_id AND member_id = :member_id"
        const val OF_MEMBERS_SQL = "SELECT $COLUMNS FROM slack_links WHERE org_id = :org_id " +
            "AND member_id = ANY(CAST(string_to_array(CAST(:members AS TEXT), ',') AS UUID[]))"
        const val MEMBER_OF_SQL = "SELECT member_id FROM slack_links WHERE org_id = :org_id " +
            "AND slack_user_id = :slack_user_id"
        const val SAVE_SQL = "INSERT INTO slack_links (org_id, member_id, slack_user_id, linked_at) " +
            "VALUES (:org_id, :member_id, :slack_user_id, :linked_at) ON CONFLICT (org_id, member_id) " +
            "DO UPDATE SET slack_user_id = EXCLUDED.slack_user_id, linked_at = EXCLUDED.linked_at"
        const val REMOVE_SQL = "DELETE FROM slack_links WHERE org_id = :org_id AND member_id = :member_id"
        const val DELETE_SQL = "DELETE FROM slack_links WHERE org_id = :org_id"
    }
}

/**
 * [SlackApprovalMessagesRepository] on `slack_approval_messages` (version 2 of [SlackMigrations]), every statement
 * confined to its organization.
 *
 * @param driver The database.
 */
class PostgresSlackApprovalMessagesRepository(private val driver: Driver) : SlackApprovalMessagesRepository {
    override suspend fun save(orgId: Uuid, message: SlackApprovalMessage) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(SAVE_SQL).bind("org_id", orgId).bind("approval_id", message.approvalId)
                    .bind("gate_id", message.gateId).bind("slack_channel", message.posted.channel)
                    .bind("ts", message.posted.ts),
            ).getOrThrow()
        }
    }

    override suspend fun ofGate(orgId: Uuid, gateId: Uuid): List<SlackApprovalMessage> =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(Statement.create(OF_GATE_SQL).bind("org_id", orgId).bind("gate_id", gateId))
                .getOrThrow().map { row ->
                    SlackApprovalMessage(
                        approvalId = row.get("approval_id").asUuid(),
                        gateId = row.get("gate_id").asUuid(),
                        posted = SlackPosted(row.get("slack_channel").asString(), row.get("ts").asString()),
                    )
                }
        }

    override suspend fun deleteAll(orgId: Uuid) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(Statement.create(DELETE_SQL).bind("org_id", orgId)).getOrThrow()
        }
    }

    private companion object {
        const val SAVE_SQL = "INSERT INTO slack_approval_messages (org_id, approval_id, gate_id, slack_channel, ts) " +
            "VALUES (:org_id, :approval_id, :gate_id, :slack_channel, :ts) ON CONFLICT (org_id, approval_id) " +
            "DO UPDATE SET slack_channel = EXCLUDED.slack_channel, ts = EXCLUDED.ts"
        const val OF_GATE_SQL = "SELECT approval_id, gate_id, slack_channel, ts FROM slack_approval_messages " +
            "WHERE org_id = :org_id AND gate_id = :gate_id"
        const val DELETE_SQL = "DELETE FROM slack_approval_messages WHERE org_id = :org_id"
    }
}
