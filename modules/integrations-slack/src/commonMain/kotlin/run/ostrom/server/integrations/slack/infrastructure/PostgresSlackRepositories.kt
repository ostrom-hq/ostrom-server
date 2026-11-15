// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.infrastructure

import run.ostrom.server.integrations.slack.domain.SlackApprovalMessage
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessagesRepository
import run.ostrom.server.integrations.slack.domain.SlackLinksRepository
import run.ostrom.server.integrations.slack.domain.SlackPosted
import run.ostrom.server.integrations.slack.domain.StoredSlackLink
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.Uuid

/**
 * [SlackLinksRepository] on `slack_links` (version 1 of [SlackMigrations]): every statement filters on its
 * organization and runs in it (`inOrg`), under row-level security.
 *
 * @param database The serving role's pool.
 */
class PostgresSlackLinksRepository(private val database: JdbcDatabase) : SlackLinksRepository {
    override suspend fun of(orgId: Uuid, memberId: Uuid): StoredSlackLink? = database.inOrg(orgId) {
        linksOf(orgId, setOf(memberId)).singleOrNull()?.toLink()
    }

    override suspend fun ofMembers(orgId: Uuid, memberIds: Set<Uuid>): List<StoredSlackLink> {
        if (memberIds.isEmpty()) return emptyList()
        return database.inOrg(orgId) { linksOf(orgId, memberIds).map { it.toLink() } }
    }

    override suspend fun memberOf(orgId: Uuid, slackUserId: String): Uuid? = database.inOrg(orgId) {
        memberOfSlackUser(orgId, slackUserId).singleOrNull()?.get(SlackLinks.memberId)
    }

    override suspend fun save(orgId: Uuid, link: StoredSlackLink) {
        database.inOrg(orgId) {
            val l = SlackLinks
            l.upsert(
                l.orgId,
                l.memberId,
                onUpdate = {
                    it[l.slackUserId] = insertValue(l.slackUserId)
                    it[l.linkedAt] = insertValue(l.linkedAt)
                },
            ) {
                it[this.orgId] = orgId
                it[memberId] = link.memberId
                it[slackUserId] = link.slackUserId
                it[linkedAt] = link.linkedAt
            }
        }
    }

    override suspend fun remove(orgId: Uuid, memberId: Uuid) {
        database.inOrg(orgId) {
            SlackLinks.deleteWhere { (this.orgId eq orgId) and (this.memberId eq memberId) }
        }
    }

    override suspend fun deleteAll(orgId: Uuid) {
        database.inOrg(orgId) { SlackLinks.deleteWhere { this.orgId eq orgId } }
    }

    private fun ResultRow.toLink() = StoredSlackLink(
        memberId = this[SlackLinks.memberId],
        slackUserId = this[SlackLinks.slackUserId],
        linkedAt = this[SlackLinks.linkedAt],
    )
}

/** The links of [memberIds] in [orgId], one each at most: by the primary key, bounded by the members asked for. */
internal fun linksOf(orgId: Uuid, memberIds: Set<Uuid>): Query {
    val l = SlackLinks
    return l.select(l.memberId, l.slackUserId, l.linkedAt)
        .where { (l.orgId eq orgId) and (l.memberId inList memberIds) }
        .limit(memberIds.size)
}

/** The member of [orgId] linked to [slackUserId]: by the unique `(org_id, slack_user_id)`. */
internal fun memberOfSlackUser(orgId: Uuid, slackUserId: String): Query {
    val l = SlackLinks
    return l.select(l.memberId).where { (l.orgId eq orgId) and (l.slackUserId eq slackUserId) }
}

/**
 * [SlackApprovalMessagesRepository] on `slack_approval_messages` (version 2 of [SlackMigrations]): every statement
 * filters on its organization and runs in it (`inOrg`), under row-level security.
 *
 * @param database The serving role's pool.
 */
class PostgresSlackApprovalMessagesRepository(private val database: JdbcDatabase) : SlackApprovalMessagesRepository {
    override suspend fun save(orgId: Uuid, message: SlackApprovalMessage) {
        database.inOrg(orgId) {
            val m = SlackApprovalMessages
            m.upsert(
                m.orgId,
                m.approvalId,
                onUpdate = {
                    it[m.slackChannel] = insertValue(m.slackChannel)
                    it[m.ts] = insertValue(m.ts)
                },
            ) {
                it[this.orgId] = orgId
                it[approvalId] = message.approvalId
                it[gateId] = message.gateId
                it[slackChannel] = message.posted.channel
                it[ts] = message.posted.ts
            }
        }
    }

    override suspend fun ofGate(orgId: Uuid, gateId: Uuid): List<SlackApprovalMessage> = database.inOrg(orgId) {
        messagesOfGate(orgId, gateId).map { row ->
            SlackApprovalMessage(
                approvalId = row[SlackApprovalMessages.approvalId],
                gateId = row[SlackApprovalMessages.gateId],
                posted = SlackPosted(row[SlackApprovalMessages.slackChannel], row[SlackApprovalMessages.ts]),
            )
        }
    }

    override suspend fun deleteAll(orgId: Uuid) {
        database.inOrg(orgId) { SlackApprovalMessages.deleteWhere { this.orgId eq orgId } }
    }
}

/**
 * The messages of [gateId]'s requests, one per approver told: by the index `(org_id, gate_id)`, at most
 * [MAX_MESSAGES_OF_A_GATE].
 */
internal fun messagesOfGate(orgId: Uuid, gateId: Uuid): Query {
    val m = SlackApprovalMessages
    return m.select(m.approvalId, m.gateId, m.slackChannel, m.ts)
        .where { (m.orgId eq orgId) and (m.gateId eq gateId) }
        .orderBy(m.approvalId to SortOrder.ASC)
        .limit(MAX_MESSAGES_OF_A_GATE)
}

/** A gate asks its approvers, an organization's members with the right: the bound, far above a real gate's. */
internal const val MAX_MESSAGES_OF_A_GATE = 1_000
