// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.infrastructure.inTenant
import run.ostrom.server.sharing.domain.GroupMember
import run.ostrom.server.sharing.domain.GroupRepository
import run.ostrom.server.sharing.domain.GroupRules
import run.ostrom.server.sharing.domain.SharingGroup
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The organization's groups by name (the request path of `GET /groups`). */
internal const val GROUPS_OF_ORG_SQL: String =
    "SELECT id, org_id, name, created_at FROM sharing_groups WHERE org_id = :org_id " +
        "ORDER BY lower(name), id LIMIT ${GroupRules.MAX_GROUPS}"

/** The members of the organization's groups. */
internal const val GROUP_MEMBERS_OF_ORG_SQL: String =
    "SELECT group_id, member_id, paused_until FROM sharing_group_members WHERE org_id = :org_id"

/** The accounts lent to the organization's groups. */
internal const val GROUP_ACCOUNTS_OF_ORG_SQL: String =
    "SELECT group_id, account_id FROM sharing_group_accounts WHERE org_id = :org_id"

/** One group (the request path of `GET /groups/{id}` and of every write). */
internal const val GROUP_SQL: String =
    "SELECT id, org_id, name, created_at FROM sharing_groups WHERE org_id = :org_id AND id = :id"

/** The members of one group. */
internal const val GROUP_MEMBERS_SQL: String =
    "SELECT group_id, member_id, paused_until FROM sharing_group_members WHERE org_id = :org_id AND group_id = :id"

/** The accounts lent to one group. */
internal const val GROUP_ACCOUNTS_SQL: String =
    "SELECT group_id, account_id FROM sharing_group_accounts WHERE org_id = :org_id AND group_id = :id"

/** How many groups the organization has (before a new one). */
internal const val GROUPS_COUNT_SQL: String = "SELECT count(*) AS n FROM sharing_groups WHERE org_id = :org_id"

/** Another group of the organization with this name, whatever the case. */
internal const val GROUP_NAME_TAKEN_SQL: String =
    "SELECT id FROM sharing_groups WHERE org_id = :org_id AND lower(name) = lower(:name) AND id <> :id LIMIT 1"

/**
 * [GroupRepository] on PostgreSQL ([SharingMigrations]). Every query runs confined to the organization it names
 * (`fetchInOrg`, `executeInOrg`, `inTenant`): row-level security hides and refuses the rows of every other one, and
 * an account is lent only when the organization's own `sharing_accounts` shows it.
 *
 * @param driver The database.
 */
class GroupRepositoryImpl(private val driver: Driver) : GroupRepository {
    override suspend fun list(orgId: Uuid): List<SharingGroup> = read(
        orgId,
        Statement.create(GROUPS_OF_ORG_SQL),
        Statement.create(GROUP_MEMBERS_OF_ORG_SQL),
        Statement.create(GROUP_ACCOUNTS_OF_ORG_SQL),
    )

    override suspend fun get(orgId: Uuid, id: Uuid): SharingGroup? = read(
        orgId,
        Statement.create(GROUP_SQL).bind("id", id),
        Statement.create(GROUP_MEMBERS_SQL).bind("id", id),
        Statement.create(GROUP_ACCOUNTS_SQL).bind("id", id),
    ).singleOrNull()

    override suspend fun count(orgId: Uuid): Int = driver.fetchInOrg(
        orgId,
        Statement.create(GROUPS_COUNT_SQL).bind("org_id", orgId),
    ).getOrThrow().single().get("n").asInt()

    override suspend fun create(group: SharingGroup): Boolean = driver.inTenant(group.orgId) {
        val made = fetchAll(
            Statement.create(
                "INSERT INTO sharing_groups (id, org_id, name, created_at) " +
                    "VALUES (:id, :org_id, :name, :created_at) ON CONFLICT DO NOTHING RETURNING id",
            )
                .bind("id", group.id)
                .bind("org_id", group.orgId)
                .bind("name", group.name)
                .bind("created_at", group.createdAt),
        ).getOrThrow().toList().isNotEmpty()
        if (made) {
            group.members.forEach { putMember(group.orgId, group.id, it.memberId) }
            lend(group.orgId, group.id, group.accounts)
        }
        made
    }

    override suspend fun update(orgId: Uuid, id: Uuid, name: String?, accounts: Set<Uuid>?): Boolean =
        driver.inTenant(orgId) {
            val taken = name != null && fetchAll(
                Statement.create(GROUP_NAME_TAKEN_SQL).bind("org_id", orgId).bind("id", id).bind("name", name),
            ).getOrThrow().toList().isNotEmpty()
            if (!taken) {
                if (name != null) {
                    execute(
                        Statement.create("UPDATE sharing_groups SET name = :name WHERE org_id = :org_id AND id = :id")
                            .bind("org_id", orgId)
                            .bind("id", id)
                            .bind("name", name),
                    ).getOrThrow()
                }
                if (accounts != null) {
                    execute(
                        Statement.create("DELETE FROM sharing_group_accounts WHERE org_id = :org_id AND group_id = :id")
                            .bind("org_id", orgId)
                            .bind("id", id),
                    ).getOrThrow()
                    lend(orgId, id, accounts)
                }
            }
            !taken
        }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = driver.executeInOrg(
        orgId,
        Statement.create("DELETE FROM sharing_groups WHERE org_id = :org_id AND id = :id")
            .bind("org_id", orgId)
            .bind("id", id),
    ).getOrThrow() == 1L

    override suspend fun addMember(orgId: Uuid, id: Uuid, memberId: Uuid) {
        driver.inTenant(orgId) { putMember(orgId, id, memberId) }
    }

    override suspend fun removeMember(orgId: Uuid, id: Uuid, memberId: Uuid): Boolean = driver.executeInOrg(
        orgId,
        Statement.create(
            "DELETE FROM sharing_group_members WHERE org_id = :org_id AND group_id = :id AND member_id = :member",
        )
            .bind("org_id", orgId)
            .bind("id", id)
            .bind("member", memberId),
    ).getOrThrow() == 1L

    override suspend fun pauseMember(orgId: Uuid, id: Uuid, memberId: Uuid, until: Instant): Boolean =
        driver.executeInOrg(
            orgId,
            Statement.create(
                "UPDATE sharing_group_members SET paused_until = :until " +
                    "WHERE org_id = :org_id AND group_id = :id AND member_id = :member",
            )
                .bind("org_id", orgId)
                .bind("id", id)
                .bind("member", memberId)
                .bind("until", until),
        ).getOrThrow() == 1L

    /** Reads groups with their members and accounts; each statement binds `:org_id` here. */
    private suspend fun read(
        orgId: Uuid,
        groups: Statement,
        members: Statement,
        accounts: Statement,
    ): List<SharingGroup> = driver.inTenant(orgId) {
        val heads = fetchAll(groups.bind("org_id", orgId)).getOrThrow().toList()
        val memberRows = fetchAll(members.bind("org_id", orgId)).getOrThrow().toList()
            .groupBy({ it.get("group_id").asUuid() }) {
                GroupMember(it.get("member_id").asUuid(), it.get("paused_until").asInstantOrNull())
            }
        val accountRows = fetchAll(accounts.bind("org_id", orgId)).getOrThrow().toList()
            .groupBy({ it.get("group_id").asUuid() }) { it.get("account_id").asUuid() }
        heads.map { row ->
            val id = row.get("id").asUuid()
            SharingGroup(
                id = id,
                orgId = row.get("org_id").asUuid(),
                name = row.get("name").asString(),
                members = memberRows[id].orEmpty().sortedBy { it.memberId.toString() },
                accounts = accountRows[id].orEmpty().toSet(),
                createdAt = row.get("created_at").asInstant(),
            )
        }
    }
}

/** Adds a member to a group; one already there keeps their pause. */
private suspend fun Transaction.putMember(orgId: Uuid, groupId: Uuid, memberId: Uuid) {
    execute(
        Statement.create(
            "INSERT INTO sharing_group_members (org_id, group_id, member_id, paused_until) " +
                "VALUES (:org_id, :group_id, :member, NULL) ON CONFLICT DO NOTHING",
        )
            .bind("org_id", orgId)
            .bind("group_id", groupId)
            .bind("member", memberId),
    ).getOrThrow()
}

/**
 * Lends [accounts] to a group: each through the organization's own row of `sharing_accounts`, so an id of another
 * organization (which a foreign key alone would accept) lends nothing.
 */
private suspend fun Transaction.lend(orgId: Uuid, groupId: Uuid, accounts: Set<Uuid>) {
    accounts.forEach { account ->
        execute(
            Statement.create(
                "INSERT INTO sharing_group_accounts (org_id, group_id, account_id) " +
                    "SELECT org_id, :group_id, id FROM sharing_accounts WHERE org_id = :org_id AND id = :account " +
                    "ON CONFLICT DO NOTHING",
            )
                .bind("org_id", orgId)
                .bind("group_id", groupId)
                .bind("account", account),
        ).getOrThrow()
    }
}
