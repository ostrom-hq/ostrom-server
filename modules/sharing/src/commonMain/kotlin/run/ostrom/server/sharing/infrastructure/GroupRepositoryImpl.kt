// SPDX-License-Identifier: Apache-2.0
// The repository and its reads, one function each, so that the query-plan test explains the very statements it sends.
@file:Suppress("TooManyFunctions")

package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.sharing.domain.GroupMember
import run.ostrom.server.sharing.domain.GroupRepository
import run.ostrom.server.sharing.domain.GroupRules
import run.ostrom.server.sharing.domain.SharingGroup
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GroupRepository] on PostgreSQL ([SharingMigrations]) through Exposed. Every query runs confined to the organization
 * it names (`inOrg`): row-level security hides and refuses the rows of every other one, and an account is lent only
 * when the organization's own `sharing_accounts` shows it.
 *
 * @param database The serving role's pool.
 */
class GroupRepositoryImpl(private val database: JdbcDatabase) : GroupRepository {
    override suspend fun list(orgId: Uuid): List<SharingGroup> = database.inOrg(orgId) {
        read(groupsOfOrg(orgId), groupMembersOfOrg(orgId), groupAccountsOfOrg(orgId))
    }

    override suspend fun get(orgId: Uuid, id: Uuid): SharingGroup? = database.inOrg(orgId) {
        read(groupOf(orgId, id), groupMembersOf(orgId, id), groupAccountsOf(orgId, id)).singleOrNull()
    }

    override suspend fun count(orgId: Uuid): Int = database.inOrg(orgId) {
        val n = SharingGroups.id.count()
        groupsCount(orgId, n).single()[n].toInt()
    }

    override suspend fun create(group: SharingGroup): Boolean = database.inOrg(group.orgId) {
        val g = SharingGroups
        val made = g.insertIgnore {
            it[id] = group.id
            it[orgId] = group.orgId
            it[name] = group.name
            it[createdAt] = group.createdAt
        }.insertedCount == 1
        if (made) {
            group.members.forEach { putMember(group.orgId, group.id, it.memberId) }
            lend(group.orgId, group.id, group.accounts)
        }
        made
    }

    override suspend fun update(orgId: Uuid, id: Uuid, name: String?, accounts: Set<Uuid>?): Boolean =
        database.inOrg(orgId) {
            val taken = name != null && !groupNameTaken(orgId, id, name).empty()
            if (!taken) {
                val g = SharingGroups
                if (name != null) g.update({ (g.orgId eq orgId) and (g.id eq id) }) { it[g.name] = name }
                if (accounts != null) {
                    val ga = SharingGroupAccounts
                    ga.deleteWhere { (ga.orgId eq orgId) and (ga.groupId eq id) }
                    lend(orgId, id, accounts)
                }
            }
            !taken
        }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = database.inOrg(orgId) {
        SharingGroups.deleteWhere { (SharingGroups.orgId eq orgId) and (SharingGroups.id eq id) } == 1
    }

    override suspend fun addMember(orgId: Uuid, id: Uuid, memberId: Uuid) {
        database.inOrg(orgId) { putMember(orgId, id, memberId) }
    }

    override suspend fun removeMember(orgId: Uuid, id: Uuid, memberId: Uuid): Boolean = database.inOrg(orgId) {
        val m = SharingGroupMembers
        m.deleteWhere { (m.orgId eq orgId) and (m.groupId eq id) and (m.memberId eq memberId) } == 1
    }

    override suspend fun pauseMember(orgId: Uuid, id: Uuid, memberId: Uuid, until: Instant): Boolean =
        database.inOrg(orgId) {
            val m = SharingGroupMembers
            m.update({ (m.orgId eq orgId) and (m.groupId eq id) and (m.memberId eq memberId) }) {
                it[pausedUntil] = until
            } == 1
        }
}

/** Reads groups with their members and accounts. */
private fun read(groups: Query, members: Query, accounts: Query): List<SharingGroup> {
    val g = SharingGroups
    val m = SharingGroupMembers
    val ga = SharingGroupAccounts
    val heads = groups.toList()
    val memberRows = members.toList().groupBy({ it[m.groupId] }) { GroupMember(it[m.memberId], it[m.pausedUntil]) }
    val accountRows = accounts.toList().groupBy({ it[ga.groupId] }) { it[ga.accountId] }
    return heads.map { row ->
        val id = row[g.id]
        SharingGroup(
            id = id,
            orgId = row[g.orgId],
            name = row[g.name],
            members = memberRows[id].orEmpty().sortedBy { it.memberId.toString() },
            accounts = accountRows[id].orEmpty().toSet(),
            createdAt = row[g.createdAt],
        )
    }
}

/** Adds a member to a group; one already there keeps their pause. */
private fun putMember(orgId: Uuid, groupId: Uuid, memberId: Uuid) {
    SharingGroupMembers.insertIgnore {
        it[this.orgId] = orgId
        it[this.groupId] = groupId
        it[this.memberId] = memberId
        it[pausedUntil] = null
    }
}

/**
 * Lends [accounts] to a group: each through the organization's own row of `sharing_accounts`, so an id of another
 * organization (which a foreign key alone would accept) lends nothing.
 */
private fun lend(orgId: Uuid, groupId: Uuid, accounts: Set<Uuid>) {
    val a = SharingAccounts
    val ga = SharingGroupAccounts
    accounts.forEach { account ->
        val own = a.select(a.orgId, QueryParameter(groupId, UuidColumnType()), a.id)
            .where { (a.orgId eq orgId) and (a.id eq account) }
        ga.insertIgnore(own, listOf(ga.orgId, ga.groupId, ga.accountId))
    }
}

private val GROUP_COLUMNS = SharingGroups.run { listOf(id, orgId, name, createdAt) }

/** The organization's groups by name (the request path of `GET /groups`). */
internal fun groupsOfOrg(orgId: Uuid): Query = SharingGroups.select(GROUP_COLUMNS)
    .where { SharingGroups.orgId eq orgId }
    .orderBy(SharingGroups.name.lowerCase() to SortOrder.ASC, SharingGroups.id to SortOrder.ASC)
    .limit(GroupRules.MAX_GROUPS)

/** The members of the organization's groups. */
internal fun groupMembersOfOrg(orgId: Uuid): Query = SharingGroupMembers.run {
    select(groupId, memberId, pausedUntil).where { this@run.orgId eq orgId }
}

/** The accounts lent to the organization's groups. */
internal fun groupAccountsOfOrg(orgId: Uuid): Query = SharingGroupAccounts.run {
    select(groupId, accountId).where { this@run.orgId eq orgId }
}

/** One group (the request path of `GET /groups/{id}` and of every write). */
internal fun groupOf(orgId: Uuid, id: Uuid): Query = SharingGroups.select(GROUP_COLUMNS)
    .where { (SharingGroups.orgId eq orgId) and (SharingGroups.id eq id) }

/** The members of one group. */
internal fun groupMembersOf(orgId: Uuid, id: Uuid): Query = SharingGroupMembers.run {
    select(groupId, memberId, pausedUntil).where { (this@run.orgId eq orgId) and (groupId eq id) }
}

/** The accounts lent to one group. */
internal fun groupAccountsOf(orgId: Uuid, id: Uuid): Query = SharingGroupAccounts.run {
    select(groupId, accountId).where { (this@run.orgId eq orgId) and (groupId eq id) }
}

/** How many groups the organization has (before a new one), counted in [n]. */
internal fun groupsCount(orgId: Uuid, n: Count): Query =
    SharingGroups.select(n).where { SharingGroups.orgId eq orgId }

/** Another group of the organization with this name, whatever the case. */
internal fun groupNameTaken(orgId: Uuid, id: Uuid, name: String): Query = SharingGroups.select(SharingGroups.id)
    .where {
        (SharingGroups.orgId eq orgId) and (SharingGroups.name.lowerCase() eq stringParam(name).lowerCase()) and
            (SharingGroups.id neq id)
    }
    .limit(1)
