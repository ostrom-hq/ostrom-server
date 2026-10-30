// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.database.inOrg
import dev.ostrom.server.orgs.domain.AlreadyMemberException
import dev.ostrom.server.orgs.domain.Invitation
import dev.ostrom.server.orgs.domain.InvitationsRepository
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.MembersRepository
import dev.ostrom.server.orgs.domain.Membership
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.orgs.domain.SlugTakenException
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val ORG_COLUMNS = "o.id, o.slug, o.name, o.github_org_id, o.free_claim, o.created_at"
private const val MEMBER_COLUMNS = "m.id AS member_id, m.org_id, m.user_id, m.role, m.joined_at"
private const val INVITATION_COLUMNS =
    "id, org_id, email, role, invited_by, created_at, expires_at, max_uses, uses, revoked_at"
private const val LIVE_INVITATION =
    "revoked_at IS NULL AND expires_at > :now AND (max_uses IS NULL OR uses < max_uses)"

/**
 * [OrgsRepository] on PostgreSQL.
 *
 * @param driver The database.
 */
class OrgsRepositoryImpl(private val driver: Driver) : OrgsRepository {
    override suspend fun create(
        org: Org,
        owner: Member,
        isPaying: suspend (Uuid) -> Boolean,
    ): Org? =
        try {
            driver.transaction {
                val holder = org.freeClaim?.let { claim -> blockingHolder(this, claim, isPaying) }
                if (holder == null) {
                    execute(
                            Statement.create(
                                    "INSERT INTO orgs_organizations (id, slug, name, github_org_id, free_claim, " +
                                        "created_at) " +
                                        "VALUES (:id, :slug, :name, :github, :claim, :at)"
                                )
                                .bind("id", org.id)
                                .bind("slug", org.slug)
                                .bind("name", org.name)
                                .bind("github", org.githubOrgId)
                                .bind("claim", org.freeClaim)
                                .bind("at", org.createdAt)
                        )
                        .getOrThrow()
                    insertMember(this, owner)
                }
                holder
            }
        } catch (error: SQLError) {
            throw if (error.kind == SQLError.Kind.UniqueViolation) SlugTakenException() else error
        }

    // Serializes the creations of one claim until the transaction ends, so two at once cannot both
    // take it.
    private suspend fun blockingHolder(
        executor: QueryExecutor,
        claim: String,
        isPaying: suspend (Uuid) -> Boolean,
    ): Org? {
        executor
            .execute(
                Statement.create("SELECT pg_advisory_xact_lock(hashtext(:claim))")
                    .bind("claim", claim)
            )
            .getOrThrow()
        val holders =
            executor
                .fetchAll(
                    Statement.create(
                            "SELECT $ORG_COLUMNS FROM orgs_organizations o WHERE o.free_claim = :claim AND " +
                                "o.deleted_at IS NULL"
                        )
                        .bind("claim", claim)
                )
                .getOrThrow()
                .map { it.toOrg() }
        return holders.firstOrNull { !isPaying(it.id) }
    }

    override suspend fun get(id: Uuid): Org? =
        driver.inOrg(id) {
            fetchAll(
                    Statement.create(
                            "SELECT $ORG_COLUMNS FROM orgs_organizations o WHERE o.id = :id AND o.deleted_at IS NULL"
                        )
                        .bind("id", id)
                )
                .getOrThrow()
                .firstOrNull()
                ?.toOrg()
        }

    override suspend fun findBySlug(slug: String): Org? =
        driver
            .fetchAll(
                Statement.create(
                        "SELECT $ORG_COLUMNS FROM orgs_organizations o WHERE o.slug = :slug AND o.deleted_at IS NULL"
                    )
                    .bind("slug", slug)
            )
            .getOrThrow()
            .firstOrNull()
            ?.toOrg()

    override suspend fun slugTaken(slug: String): Boolean =
        driver
            .fetchAll(
                Statement.create("SELECT 1 FROM orgs_organizations WHERE slug = :slug")
                    .bind("slug", slug)
            )
            .getOrThrow()
            .firstOrNull() != null

    override suspend fun rename(id: Uuid, name: String) {
        driver.inOrg(id) {
            execute(
                    Statement.create("UPDATE orgs_organizations SET name = :name WHERE id = :id")
                        .bind("name", name)
                        .bind("id", id)
                )
                .getOrThrow()
        }
    }

    override suspend fun delete(id: Uuid, at: Instant) {
        driver.inOrg(id) {
            execute(
                    Statement.create("DELETE FROM orgs_invitations WHERE org_id = :id")
                        .bind("id", id)
                )
                .getOrThrow()
            execute(Statement.create("DELETE FROM orgs_members WHERE org_id = :id").bind("id", id))
                .getOrThrow()
            execute(
                    Statement.create(
                            "UPDATE orgs_organizations SET deleted_at = :at, free_claim = NULL WHERE id = :id"
                        )
                        .bind("at", at)
                        .bind("id", id)
                )
                .getOrThrow()
        }
    }

    override suspend fun listOfUser(userId: Uuid): List<Membership> =
        driver
            .fetchAll(
                Statement.create(
                        "SELECT $ORG_COLUMNS, $MEMBER_COLUMNS FROM orgs_members m JOIN orgs_organizations o ON o.id " +
                            "= m.org_id " +
                            "WHERE m.user_id = :user AND o.deleted_at IS NULL ORDER BY o.name, o.slug"
                    )
                    .bind("user", userId)
            )
            .getOrThrow()
            .map { Membership(it.toOrg(), it.toMember()) }
}

/**
 * [MembersRepository] on PostgreSQL: every query runs confined to its organization (row-level
 * security).
 *
 * @param driver The database.
 */
class MembersRepositoryImpl(private val driver: Driver) : MembersRepository {
    override suspend fun findByUser(orgId: Uuid, userId: Uuid): Member? =
        driver.inOrg(orgId) {
            fetchAll(
                    Statement.create(
                            "SELECT $MEMBER_COLUMNS FROM orgs_members m WHERE m.org_id = :org AND m.user_id = :user"
                        )
                        .bind("org", orgId)
                        .bind("user", userId)
                )
                .getOrThrow()
                .firstOrNull()
                ?.toMember()
        }

    override suspend fun findById(orgId: Uuid, memberId: Uuid): Member? =
        driver.inOrg(orgId) {
            fetchAll(
                    Statement.create(
                            "SELECT $MEMBER_COLUMNS FROM orgs_members m WHERE m.org_id = :org AND m.id = :id"
                        )
                        .bind("org", orgId)
                        .bind("id", memberId)
                )
                .getOrThrow()
                .firstOrNull()
                ?.toMember()
        }

    override suspend fun list(orgId: Uuid): List<Member> =
        driver.inOrg(orgId) {
            fetchAll(
                    Statement.create(
                            "SELECT $MEMBER_COLUMNS FROM orgs_members m WHERE m.org_id = :org ORDER BY m.joined_at, " +
                                "m.id"
                        )
                        .bind("org", orgId)
                )
                .getOrThrow()
                .map { it.toMember() }
        }

    override suspend fun count(orgId: Uuid): Int =
        driver.inOrg(orgId) {
            fetchAll(
                    Statement.create("SELECT COUNT(*) AS n FROM orgs_members WHERE org_id = :org")
                        .bind("org", orgId)
                )
                .getOrThrow()
                .first()
                .get("n")
                .asInt()
        }

    override suspend fun changeRole(orgId: Uuid, memberId: Uuid, role: OrgRole) {
        driver.inOrg(orgId) {
            execute(
                    Statement.create(
                            "UPDATE orgs_members SET role = :role WHERE org_id = :org AND id = :id"
                        )
                        .bind("role", role.key())
                        .bind("org", orgId)
                        .bind("id", memberId)
                )
                .getOrThrow()
        }
    }

    override suspend fun remove(orgId: Uuid, memberId: Uuid) {
        driver.inOrg(orgId) {
            execute(
                    Statement.create("DELETE FROM orgs_members WHERE org_id = :org AND id = :id")
                        .bind("org", orgId)
                        .bind("id", memberId)
                )
                .getOrThrow()
        }
    }

    override suspend fun transferOwnership(orgId: Uuid, oldOwnerId: Uuid, newOwnerId: Uuid) {
        driver.inOrg(orgId) {
            // The former owner steps down first: an organization has one owner at a time
            // (orgs_members_one_owner).
            setRole(this, orgId, oldOwnerId, OrgRole.ADMIN)
            setRole(this, orgId, newOwnerId, OrgRole.OWNER)
        }
    }

    private suspend fun setRole(
        executor: QueryExecutor,
        orgId: Uuid,
        memberId: Uuid,
        role: OrgRole,
    ) {
        executor
            .execute(
                Statement.create(
                        "UPDATE orgs_members SET role = :role WHERE org_id = :org AND id = :id"
                    )
                    .bind("role", role.key())
                    .bind("org", orgId)
                    .bind("id", memberId)
            )
            .getOrThrow()
    }
}

/**
 * [InvitationsRepository] on PostgreSQL.
 *
 * @param driver The database.
 */
class InvitationsRepositoryImpl(private val driver: Driver) : InvitationsRepository {
    override suspend fun create(invitation: Invitation, tokenHash: String) {
        driver.inOrg(invitation.orgId) {
            execute(
                    Statement.create(
                            "INSERT INTO orgs_invitations (id, org_id, token_hash, email, role, invited_by, " +
                                "created_at, expires_at, " +
                                "max_uses, uses) VALUES (:id, :org, :hash, :email, :role, :by, :at, :expires, :max, " +
                                ":uses)"
                        )
                        .bind("id", invitation.id)
                        .bind("org", invitation.orgId)
                        .bind("hash", tokenHash)
                        .bind("email", invitation.email)
                        .bind("role", invitation.role.key())
                        .bind("by", invitation.invitedBy)
                        .bind("at", invitation.createdAt)
                        .bind("expires", invitation.expiresAt)
                        .bind("max", invitation.maxUses)
                        .bind("uses", invitation.uses)
                )
                .getOrThrow()
        }
    }

    override suspend fun listLive(orgId: Uuid, now: Instant): List<Invitation> =
        driver.inOrg(orgId) {
            fetchAll(
                    Statement.create(
                            "SELECT $INVITATION_COLUMNS FROM orgs_invitations WHERE org_id = :org AND " +
                                "$LIVE_INVITATION " +
                                "ORDER BY created_at DESC, id"
                        )
                        .bind("org", orgId)
                        .bind("now", now)
                )
                .getOrThrow()
                .map { it.toInvitation() }
        }

    override suspend fun pendingEmails(orgId: Uuid, now: Instant): Set<String> =
        listLive(orgId, now).mapNotNull { it.email }.toSet()

    override suspend fun findLiveByTokenHash(tokenHash: String, now: Instant): Invitation? =
        driver
            .fetchAll(
                Statement.create(
                        "SELECT $INVITATION_COLUMNS FROM orgs_invitations i WHERE token_hash = :hash AND " +
                            "$LIVE_INVITATION " +
                            "AND EXISTS (SELECT 1 FROM orgs_organizations o WHERE o.id = i.org_id AND o.deleted_at " +
                            "IS NULL)"
                    )
                    .bind("hash", tokenHash)
                    .bind("now", now)
            )
            .getOrThrow()
            .firstOrNull()
            ?.toInvitation()

    override suspend fun revoke(orgId: Uuid, id: Uuid, at: Instant): Boolean =
        driver.inOrg(orgId) {
            execute(
                    Statement.create(
                            "UPDATE orgs_invitations SET revoked_at = :now WHERE org_id = :org AND id = :id AND " +
                                "$LIVE_INVITATION"
                        )
                        .bind("now", at)
                        .bind("org", orgId)
                        .bind("id", id)
                )
                .getOrThrow() == 1L
        }

    override suspend fun accept(invitation: Invitation, member: Member, now: Instant): Boolean =
        driver.inOrg(invitation.orgId) {
            // Counted only while still live: two people racing for the last use of a link cannot
            // both get in.
            val used =
                execute(
                        Statement.create(
                                "UPDATE orgs_invitations SET uses = uses + 1 WHERE org_id = :org AND id = :id AND " +
                                    "$LIVE_INVITATION"
                            )
                            .bind("org", invitation.orgId)
                            .bind("id", invitation.id)
                            .bind("now", now)
                    )
                    .getOrThrow() == 1L
            if (used) {
                try {
                    insertMember(this, member)
                } catch (error: SQLError) {
                    throw if (error.kind == SQLError.Kind.UniqueViolation) AlreadyMemberException()
                    else error
                }
            }
            used
        }
}

private suspend fun insertMember(executor: QueryExecutor, member: Member) {
    executor
        .execute(
            Statement.create(
                    "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) VALUES (:id, :org, :user, " +
                        ":role, :at)"
                )
                .bind("id", member.id)
                .bind("org", member.orgId)
                .bind("user", member.userId)
                .bind("role", member.role.key())
                .bind("at", member.joinedAt)
        )
        .getOrThrow()
}

/**
 * The stored name of a role.
 *
 * @return `owner`, `admin`…
 */
internal fun OrgRole.key(): String = name.lowercase()

/**
 * Reads a stored role.
 *
 * @param key The stored name.
 * @return The role.
 */
internal fun roleOf(key: String): OrgRole = OrgRole.valueOf(key.uppercase())

private fun ResultSet.Row.toOrg() =
    Org(
        id = get("id").asUuid(),
        slug = get("slug").asString(),
        name = get("name").asString(),
        githubOrgId = get("github_org_id").asStringOrNull()?.toLong(),
        freeClaim = get("free_claim").asStringOrNull(),
        createdAt = get("created_at").asInstant(),
    )

private fun ResultSet.Row.toMember() =
    Member(
        id = get("member_id").asUuid(),
        orgId = get("org_id").asUuid(),
        userId = get("user_id").asUuid(),
        role = roleOf(get("role").asString()),
        joinedAt = get("joined_at").asInstant(),
    )

private fun ResultSet.Row.toInvitation() =
    Invitation(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        email = get("email").asStringOrNull(),
        role = roleOf(get("role").asString()),
        invitedBy = get("invited_by").asUuid(),
        createdAt = get("created_at").asInstant(),
        expiresAt = get("expires_at").asInstant(),
        maxUses = get("max_uses").asStringOrNull()?.toInt(),
        uses = get("uses").asInt(),
        revokedAt = get("revoked_at").asInstantOrNull(),
    )
