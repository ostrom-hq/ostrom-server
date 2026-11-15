// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [OrgsMigrations] as Exposed reads and writes them. The migrations create the schema; these objects
// only name its columns and their types. The functions of `ostrom_router` that return an organization or an
// invitation (OrgsFunctions.kt) share the columns of its table.

/** The columns of an organization, in `orgs_organizations` and in what the functions return of it. */
internal open class OrgRows(tableName: String) : Table(tableName) {
    val id = uuid("id")
    val slug = text("slug")
    val name = text("name")
    val githubOrgId = long("github_org_id").nullable()
    val freeClaim = text("free_claim").nullable()
    val createdAt = instant("created_at")
    val timeZone = text("time_zone")
    val hooksDeadlineSeconds = integer("hooks_deadline_seconds")

    /** What an [run.ostrom.server.orgs.domain.Org] is read from. */
    val read get() = listOf(id, slug, name, githubOrgId, freeClaim, createdAt, timeZone, hooksDeadlineSeconds)
}

/** `orgs_organizations`: an organization (row-level security). */
internal object OrgOrganizations : OrgRows("orgs_organizations") {
    val deletedAt = instant("deleted_at").nullable()
    val purgeAfter = instant("purge_after").nullable()
    val deletionSettledAt = instant("deletion_settled_at").nullable()
    val purgedAt = instant("purged_at").nullable()
    val deletionRetryAt = instant("deletion_retry_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** `orgs_members`: an organization's members, one row per person (row-level security). */
internal object OrgMembers : Table("orgs_members") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val userId = uuid("user_id")
    val role = text("role")
    val joinedAt = instant("joined_at")

    override val primaryKey = PrimaryKey(id)
}

/** The columns of an invitation, in `orgs_invitations` and in what the function returns of it. */
internal open class InvitationRows(tableName: String) : Table(tableName) {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val email = text("email").nullable()
    val role = text("role")
    val invitedBy = uuid("invited_by")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val maxUses = integer("max_uses").nullable()
    val uses = integer("uses")
    val revokedAt = instant("revoked_at").nullable()

    /** What an [run.ostrom.server.orgs.domain.Invitation] is read from. */
    val read get() = listOf(id, orgId, email, role, invitedBy, createdAt, expiresAt, maxUses, uses, revokedAt)
}

/** `orgs_invitations`: the invitations of an organization, by the hash of their token (row-level security). */
internal object OrgInvitations : InvitationRows("orgs_invitations") {
    val tokenHash = text("token_hash")

    override val primaryKey = PrimaryKey(id)
}
