// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [HooksMigrations], as Exposed reads and writes them: the migrations create the schema, these objects
// only name its columns. Tenant tables: read and written in `JdbcDatabase.inOrg`, each query filtered on `org_id` too.

/** The organization's hooks: a name unique in its organization, the pinned version, the audience. */
internal object HooksHooks : Table("hooks_hooks") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val name = text("name")
    val pinnedVersionId = uuid("pinned_version_id").nullable()
    val allMembers = bool("all_members")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** A hook's versions and their definitions (`events`: the wire names, sorted and comma-separated). */
internal object HooksVersions : Table("hooks_versions") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val hookId = uuid("hook_id")
    val version = text("version")
    val digest = text("digest")
    val events = text("events")
    val matcher = text("matcher").nullable()
    val command = text("command")
    val timeoutSeconds = integer("timeout_seconds")
    val sizeBytes = long("size_bytes")
    val fileCount = integer("file_count")
    val publishedBy = text("published_by")
    val publishedAt = instant("published_at")

    override val primaryKey = PrimaryKey(id)
}

/** A version's files, in PostgreSQL (`BYTEA`). */
internal object HooksFiles : Table("hooks_files") {
    val versionId = uuid("version_id")
    val orgId = uuid("org_id")
    val path = text("path")
    val mode = text("mode")
    val size = integer("size")
    val sha256 = text("sha256")
    val content = binary("content")

    override val primaryKey = PrimaryKey(versionId, path)
}

/** The members' decisions on a hook; a cleared decision keeps its row (`decision` null). */
internal object HooksAssignments : Table("hooks_assignments") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val hookId = uuid("hook_id")
    val memberId = text("member_id")
    val decision = text("decision").nullable()
    val updatedBy = text("updated_by")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}
