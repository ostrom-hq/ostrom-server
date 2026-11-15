// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.write
import run.ostrom.server.sharing.domain.AccountGroups
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.InsertSelectStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [AccountGroups] on the groups' tables ([SharingMigrations] 5 and 6) through Exposed. Every query runs confined to
 * the organization it names (`inOrg`): row-level security hides the rows of every other one. Reading reuses the
 * groups' own queries ([groupAccountsOfOrg], [groupsOfOrg]): an organization has at most a hundred groups.
 *
 * @param database The serving role's pool.
 */
class AccountGroupsImpl(private val database: JdbcDatabase) : AccountGroups {
    override suspend fun of(orgId: Uuid, accountIds: Collection<Uuid>): Map<Uuid, Set<Uuid>> {
        if (accountIds.isEmpty()) return emptyMap()
        val wanted = accountIds.toSet()
        val ga = SharingGroupAccounts
        return database.inOrg(orgId) {
            groupAccountsOfOrg(orgId).map { it[ga.accountId] to it[ga.groupId] }
        }
            .filter { (account, _) -> account in wanted }
            .groupBy({ it.first }) { it.second }
            .mapValues { (_, groups) -> groups.toSet() }
    }

    override suspend fun lend(orgId: Uuid, accountId: Uuid, groups: Set<Uuid>) {
        database.inOrg(orgId) {
            write { accountGroupsDelete(orgId, accountId) }
            groups.forEach { group -> write { accountGroupLend(orgId, accountId, group) } }
        }
    }

    override suspend fun existing(orgId: Uuid, groups: Set<Uuid>): Set<Uuid> {
        if (groups.isEmpty()) return emptySet()
        return database.inOrg(orgId) { groupsOfOrg(orgId).map { it[SharingGroups.id] } }
            .filter { it in groups }
            .toSet()
    }

    override suspend fun lentTo(orgId: Uuid, memberId: Uuid, now: Instant): Set<Uuid> = database.inOrg(orgId) {
        accountsLentToMember(orgId, memberId, now).map { it[SharingGroupAccounts.accountId] }.toSet()
    }
}

/** The groups one account is lent to, dropped before it is lent again (the request path of an account's sharing). */
internal fun StatementBuilder.accountGroupsDelete(orgId: Uuid, accountId: Uuid): DeleteStatement {
    val ga = SharingGroupAccounts
    return ga.deleteWhere { (ga.orgId eq orgId) and (ga.accountId eq accountId) }
}

/**
 * Lends one account to one group: both through the organization's own rows, so a group or an account of another
 * organization (which the foreign keys alone would accept) lends nothing.
 */
internal fun StatementBuilder.accountGroupLend(orgId: Uuid, accountId: Uuid, groupId: Uuid): InsertSelectStatement {
    val a = SharingAccounts
    val g = SharingGroups
    val ga = SharingGroupAccounts
    val pair = a.join(g, JoinType.INNER, additionalConstraint = { g.orgId eq a.orgId })
        .select(a.orgId, g.id, a.id)
        .where { (a.orgId eq orgId) and (a.id eq accountId) and (g.id eq groupId) }
    return ga.insertIgnore(pair, listOf(ga.orgId, ga.groupId, ga.accountId))
}

/** The accounts lent to a member through the groups they are in and not paused in (the request path of a lease). */
internal fun accountsLentToMember(orgId: Uuid, memberId: Uuid, now: Instant): Query {
    val gm = SharingGroupMembers
    val ga = SharingGroupAccounts
    return gm.join(ga, JoinType.INNER, additionalConstraint = { (ga.orgId eq gm.orgId) and (ga.groupId eq gm.groupId) })
        .select(ga.accountId)
        .withDistinct()
        .where {
            (gm.orgId eq orgId) and (gm.memberId eq memberId) and
                (gm.pausedUntil.isNull() or (gm.pausedUntil lessEq now))
        }
}
