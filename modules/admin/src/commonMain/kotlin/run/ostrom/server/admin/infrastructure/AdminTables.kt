// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [AdminMigrations], as Exposed reads and writes them: the migrations create the schema, these objects
// only name its columns. Both are the instance's, with no organization and no row-level security.

/** The instance's administrators, removed ones kept (`removed_at`). */
internal object AdminInstanceAdmins : Table("admin_instance_admins") {
    val userId = uuid("user_id")
    /** Who added the administrator (`source`, a name [Table] already has). */
    val addedVia = text("source")
    val addedAt = instant("added_at")
    val addedBy = uuid("added_by").nullable()
    val removedAt = instant("removed_at").nullable()
    val removedBy = uuid("removed_by").nullable()
    override val primaryKey = PrimaryKey(userId)
}

/** The settings sections, one row each: plain values and sealed secrets as JSON objects. */
internal object AdminSettings : Table("admin_settings") {
    val section = text("section")
    val plainValues = text("plain_values")
    val sealedValues = text("sealed_values")
    val version = integer("version")
    val updatedAt = instant("updated_at")
    val updatedBy = uuid("updated_by").nullable()
    override val primaryKey = PrimaryKey(section)
}
