// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [SecretsMigrations], as Exposed reads and writes them: the migrations create the schema, these objects
// only name its columns. Tenant tables: read and written in `JdbcDatabase.inOrg`, each query filtered on `org_id` too.

/** The org's secrets, team-wide or a member's own: unique by `secrets_entries_name`, nulls not distinct. */
internal object SecretsEntries : Table("secrets_entries") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val name = text("name")
    val ownerMemberId = uuid("owner_member_id").nullable()
    val envelope = text("envelope")
    val version = integer("version")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")
    val updatedBy = uuid("updated_by")

    override val primaryKey = PrimaryKey(id)
}

/**
 * The variants of an environment variable, team-wide or a member's own: unique by `environment_variants_name`
 * (`lower(name)`, nulls not distinct).
 */
internal object EnvironmentVariants : Table("environment_variants") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val variable = text("variable")
    val name = text("name")
    val binding = text("binding")
    val ownerMemberId = uuid("owner_member_id").nullable()
    val envelope = text("envelope").nullable()
    val version = integer("version")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** The variant each member has for a variable, and the member's own value of a per-member variant. */
internal object EnvironmentAssignments : Table("environment_assignments") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val variable = text("variable")
    val variantId = uuid("variant_id")
    // Not `source`: a ColumnSet already has one.
    val assignedBy = text("source")
    val envelope = text("envelope").nullable()
    val assignedAt = instant("assigned_at")

    override val primaryKey = PrimaryKey(orgId, memberId, variable)
}

/** The variables a member is kept out of. */
internal object EnvironmentExclusions : Table("environment_exclusions") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val variable = text("variable")

    override val primaryKey = PrimaryKey(orgId, memberId, variable)
}
