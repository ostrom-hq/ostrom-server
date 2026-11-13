// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.infrastructure.inTenant
import run.ostrom.server.sharing.domain.AccountGroups
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The groups one account is lent to, dropped before it is lent again (the request path of an account's sharing). */
internal const val ACCOUNT_GROUPS_DELETE_SQL: String =
    "DELETE FROM sharing_group_accounts WHERE org_id = :org_id AND account_id = :account"

/**
 * Lends one account to one group: both through the organization's own rows, so a group or an account of another
 * organization (which the foreign keys alone would accept) lends nothing.
 */
internal const val ACCOUNT_GROUP_LEND_SQL: String =
    "INSERT INTO sharing_group_accounts (org_id, group_id, account_id) " +
        "SELECT a.org_id, g.id, a.id FROM sharing_accounts a JOIN sharing_groups g ON g.org_id = a.org_id " +
        "WHERE a.org_id = :org_id AND a.id = :account AND g.id = :group_id ON CONFLICT DO NOTHING"

/** The accounts lent to a member through the groups they are in and not paused in (the request path of a lease). */
internal const val ACCOUNTS_LENT_TO_MEMBER_SQL: String =
    "SELECT DISTINCT ga.account_id FROM sharing_group_members gm JOIN sharing_group_accounts ga " +
        "ON ga.org_id = gm.org_id AND ga.group_id = gm.group_id " +
        "WHERE gm.org_id = :org_id AND gm.member_id = :member " +
        "AND (gm.paused_until IS NULL OR gm.paused_until <= :now)"

/**
 * [AccountGroups] on the groups' tables ([SharingMigrations] 5 and 6). Every query runs confined to the organization
 * it names: row-level security hides the rows of every other one. Reading reuses the groups' own queries
 * ([GROUPS_OF_ORG_SQL], [GROUP_ACCOUNTS_OF_ORG_SQL]): an organization has at most a hundred groups.
 *
 * @param driver The database.
 */
class AccountGroupsImpl(private val driver: Driver) : AccountGroups {
    override suspend fun of(orgId: Uuid, accountIds: Collection<Uuid>): Map<Uuid, Set<Uuid>> {
        if (accountIds.isEmpty()) return emptyMap()
        val wanted = accountIds.toSet()
        return driver.fetchInOrg(orgId, Statement.create(GROUP_ACCOUNTS_OF_ORG_SQL).bind("org_id", orgId))
            .getOrThrow().toList()
            .map { it.get("account_id").asUuid() to it.get("group_id").asUuid() }
            .filter { (account, _) -> account in wanted }
            .groupBy({ it.first }) { it.second }
            .mapValues { (_, groups) -> groups.toSet() }
    }

    override suspend fun lend(orgId: Uuid, accountId: Uuid, groups: Set<Uuid>) {
        driver.inTenant(orgId) {
            execute(
                Statement.create(ACCOUNT_GROUPS_DELETE_SQL).bind("org_id", orgId).bind("account", accountId),
            ).getOrThrow()
            groups.forEach { group ->
                execute(
                    Statement.create(ACCOUNT_GROUP_LEND_SQL)
                        .bind("org_id", orgId)
                        .bind("account", accountId)
                        .bind("group_id", group),
                ).getOrThrow()
            }
        }
    }

    override suspend fun existing(orgId: Uuid, groups: Set<Uuid>): Set<Uuid> {
        if (groups.isEmpty()) return emptySet()
        return driver.fetchInOrg(orgId, Statement.create(GROUPS_OF_ORG_SQL).bind("org_id", orgId))
            .getOrThrow().toList()
            .map { it.get("id").asUuid() }
            .filter { it in groups }
            .toSet()
    }

    override suspend fun lentTo(orgId: Uuid, memberId: Uuid, now: Instant): Set<Uuid> = driver.fetchInOrg(
        orgId,
        Statement.create(ACCOUNTS_LENT_TO_MEMBER_SQL)
            .bind("org_id", orgId)
            .bind("member", memberId)
            .bind("now", now),
    ).getOrThrow().toList().map { it.get("account_id").asUuid() }.toSet()
}
