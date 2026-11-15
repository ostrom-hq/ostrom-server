// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.infrastructure

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessage
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessagesRepository
import run.ostrom.server.integrations.discord.domain.DiscordLinkAttempt
import run.ostrom.server.integrations.discord.domain.DiscordLinkAttemptsRepository
import run.ostrom.server.integrations.discord.domain.DiscordLinksRepository
import run.ostrom.server.integrations.discord.domain.DiscordPosted
import run.ostrom.server.integrations.discord.domain.DiscordUserTakenException
import run.ostrom.server.integrations.discord.domain.StoredDiscordLink
import run.ostrom.server.kernel.database.JdbcDatabase
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [DiscordLinksRepository] on `discord_links` ([DiscordMigrations]): every statement filters on its organization and
 * runs in it (`inOrg`), under row-level security.
 *
 * @param database The serving role's pool.
 */
class PostgresDiscordLinksRepository(private val database: JdbcDatabase) : DiscordLinksRepository {
    override suspend fun of(orgId: Uuid, memberId: Uuid): StoredDiscordLink? = database.inOrg(orgId) {
        discordLinksOf(orgId, setOf(memberId)).singleOrNull()?.toLink()
    }

    override suspend fun ofMembers(orgId: Uuid, memberIds: Set<Uuid>): List<StoredDiscordLink> {
        if (memberIds.isEmpty()) return emptyList()
        return database.inOrg(orgId) { discordLinksOf(orgId, memberIds).map { it.toLink() } }
    }

    override suspend fun memberOf(orgId: Uuid, discordUserId: String): Uuid? = database.inOrg(orgId) {
        memberOfDiscordUser(orgId, discordUserId).singleOrNull()?.get(DiscordLinks.memberId)
    }

    override suspend fun save(orgId: Uuid, link: StoredDiscordLink) {
        try {
            database.inOrg(orgId) {
                val l = DiscordLinks
                l.upsert(
                    l.orgId,
                    l.memberId,
                    onUpdate = {
                        it[l.discordUserId] = insertValue(l.discordUserId)
                        it[l.username] = insertValue(l.username)
                        it[l.dmChannelId] = insertValue(l.dmChannelId)
                        it[l.linkedAt] = insertValue(l.linkedAt)
                    },
                ) {
                    it[this.orgId] = orgId
                    it[memberId] = link.memberId
                    it[discordUserId] = link.discordUserId
                    it[username] = link.username
                    it[dmChannelId] = link.dmChannelId
                    it[linkedAt] = link.linkedAt
                }
            }
        } catch (error: ExposedSQLException) {
            // The member's own row is replaced (the upsert's arbiter); what is left is another member's account,
            // taken between the use case's read and this write.
            if (error.sqlState == UNIQUE_VIOLATION) throw DiscordUserTakenException()
            throw error
        }
    }

    override suspend fun saveDmChannel(orgId: Uuid, memberId: Uuid, discordUserId: String, channelId: String) {
        database.inOrg(orgId) {
            val l = DiscordLinks
            l.update({ (l.orgId eq orgId) and (l.memberId eq memberId) and (l.discordUserId eq discordUserId) }) {
                it[dmChannelId] = channelId
            }
        }
    }

    override suspend fun remove(orgId: Uuid, memberId: Uuid) {
        database.inOrg(orgId) {
            DiscordLinks.deleteWhere { (this.orgId eq orgId) and (this.memberId eq memberId) }
        }
    }

    override suspend fun deleteAll(orgId: Uuid) {
        database.inOrg(orgId) { DiscordLinks.deleteWhere { this.orgId eq orgId } }
    }

    private fun ResultRow.toLink() = StoredDiscordLink(
        memberId = this[DiscordLinks.memberId],
        discordUserId = this[DiscordLinks.discordUserId],
        username = this[DiscordLinks.username],
        linkedAt = this[DiscordLinks.linkedAt],
        dmChannelId = this[DiscordLinks.dmChannelId],
    )
}

/** The links of [memberIds] in [orgId], one each at most: by the primary key, bounded by the members asked for. */
internal fun discordLinksOf(orgId: Uuid, memberIds: Set<Uuid>): Query {
    val l = DiscordLinks
    return l.select(l.memberId, l.discordUserId, l.username, l.dmChannelId, l.linkedAt)
        .where { (l.orgId eq orgId) and (l.memberId inList memberIds) }
        .limit(memberIds.size)
}

/** The member of [orgId] linked to [discordUserId]: by the unique `(org_id, discord_user_id)`. */
internal fun memberOfDiscordUser(orgId: Uuid, discordUserId: String): Query {
    val l = DiscordLinks
    return l.select(l.memberId).where { (l.orgId eq orgId) and (l.discordUserId eq discordUserId) }
}

/**
 * [DiscordLinkAttemptsRepository] on `discord_link_attempts` ([DiscordMigrations]): every statement filters on its
 * organization and runs in it (`inOrg`), under row-level security.
 *
 * @param database The serving role's pool.
 */
class PostgresDiscordLinkAttemptsRepository(private val database: JdbcDatabase) : DiscordLinkAttemptsRepository {
    override suspend fun start(orgId: Uuid, attempt: DiscordLinkAttempt, now: Instant) {
        database.inOrg(orgId) {
            val a = DiscordLinkAttempts
            // One unfinished attempt per member; the organization's expired ones go with it, so the table stays small.
            a.deleteWhere { (this.orgId eq orgId) and ((memberId eq attempt.memberId) or (expiresAt lessEq now)) }
            a.insert {
                it[this.orgId] = orgId
                it[stateHash] = attempt.stateHash
                it[memberId] = attempt.memberId
                it[binding] = attempt.binding
                it[expiresAt] = attempt.expiresAt
            }
        }
    }

    override suspend fun of(orgId: Uuid, stateHash: String): DiscordLinkAttempt? = database.inOrg(orgId) {
        attemptOf(orgId, stateHash).singleOrNull()?.let { row ->
            DiscordLinkAttempt(
                stateHash = row[DiscordLinkAttempts.stateHash],
                memberId = row[DiscordLinkAttempts.memberId],
                binding = row[DiscordLinkAttempts.binding],
                expiresAt = row[DiscordLinkAttempts.expiresAt],
            )
        }
    }

    override suspend fun consume(orgId: Uuid, stateHash: String): Boolean = database.inOrg(orgId) {
        DiscordLinkAttempts.deleteWhere { (this.orgId eq orgId) and (this.stateHash eq stateHash) } > 0
    }

    override suspend fun deleteAll(orgId: Uuid) {
        database.inOrg(orgId) { DiscordLinkAttempts.deleteWhere { this.orgId eq orgId } }
    }
}

/** The attempt of [stateHash] in [orgId]: by the primary key `(org_id, state_hash)`. */
internal fun attemptOf(orgId: Uuid, stateHash: String): Query {
    val a = DiscordLinkAttempts
    return a.select(a.stateHash, a.memberId, a.binding, a.expiresAt)
        .where { (a.orgId eq orgId) and (a.stateHash eq stateHash) }
        .limit(1)
}

/**
 * [DiscordApprovalMessagesRepository] on `discord_approval_messages` ([DiscordMigrations]): every statement filters
 * on its organization and runs in it (`inOrg`), under row-level security.
 *
 * @param database The serving role's pool.
 */
class PostgresDiscordApprovalMessagesRepository(
    private val database: JdbcDatabase,
) : DiscordApprovalMessagesRepository {
    override suspend fun save(orgId: Uuid, message: DiscordApprovalMessage) {
        database.inOrg(orgId) {
            val m = DiscordApprovalMessages
            m.upsert(
                m.orgId,
                m.approvalId,
                onUpdate = {
                    it[m.channelId] = insertValue(m.channelId)
                    it[m.messageId] = insertValue(m.messageId)
                },
            ) {
                it[this.orgId] = orgId
                it[approvalId] = message.approvalId
                it[gateId] = message.gateId
                it[channelId] = message.posted.channelId
                it[messageId] = message.posted.messageId
            }
        }
    }

    override suspend fun of(orgId: Uuid, approvalId: Uuid): DiscordApprovalMessage? = database.inOrg(orgId) {
        discordMessageOf(orgId, approvalId).singleOrNull()?.let { row ->
            DiscordApprovalMessage(
                approvalId = row[DiscordApprovalMessages.approvalId],
                gateId = row[DiscordApprovalMessages.gateId],
                posted = DiscordPosted(row[DiscordApprovalMessages.channelId], row[DiscordApprovalMessages.messageId]),
            )
        }
    }

    override suspend fun deleteAll(orgId: Uuid) {
        database.inOrg(orgId) { DiscordApprovalMessages.deleteWhere { this.orgId eq orgId } }
    }
}

/** The message kept for [approvalId] in [orgId]: by the primary key `(org_id, approval_id)`. */
internal fun discordMessageOf(orgId: Uuid, approvalId: Uuid): Query {
    val m = DiscordApprovalMessages
    return m.select(m.approvalId, m.gateId, m.channelId, m.messageId)
        .where { (m.orgId eq orgId) and (m.approvalId eq approvalId) }
        .limit(1)
}

/** PostgreSQL's SQLSTATE of a unique violation. */
private const val UNIQUE_VIOLATION = "23505"
