// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [SkillsMigrations], as Exposed reads and writes them: the migrations create the schema, these objects
// only name its columns. Tenant tables: read and written in `JdbcDatabase.inOrg`, each query filtered on `org_id` too.

/** The organization's skills, team (no owner) and personal: a name unique in its organization, the pinned version. */
internal object SkillsSkills : Table("skills_skills") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val name = text("name")
    val ownerId = text("owner_id").nullable()
    val pinnedVersionId = uuid("pinned_version_id").nullable()
    val allMembers = bool("all_members")
    val retiredAt = instant("retired_at").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** A skill's versions. */
internal object SkillsVersions : Table("skills_versions") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val skillId = uuid("skill_id")
    val version = text("version")
    val digest = text("digest")
    val sizeBytes = long("size_bytes")
    val fileCount = integer("file_count")
    val publishedBy = text("published_by")
    val publishedAt = instant("published_at")
    val retiredAt = instant("retired_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** A version's files, in PostgreSQL (`BYTEA`). */
internal object SkillsFiles : Table("skills_files") {
    val versionId = uuid("version_id")
    val orgId = uuid("org_id")
    val path = text("path")
    val mode = text("mode")
    val size = integer("size")
    val sha256 = text("sha256")
    val content = binary("content")

    override val primaryKey = PrimaryKey(versionId, path)
}

/** The members' decisions on a skill; a cleared decision keeps its row (`decision` null). */
internal object SkillsAssignments : Table("skills_assignments") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val skillId = uuid("skill_id")
    val memberId = text("member_id")
    val decision = text("decision").nullable()
    val updatedBy = text("updated_by")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** What each workstation reported installing, one row per device and skill. */
internal object SkillsInstallations : Table("skills_installations") {
    val orgId = uuid("org_id")
    val deviceId = text("device_id")
    val deviceName = text("device_name")
    val memberId = text("member_id")
    val skillId = uuid("skill_id")
    val version = text("version").nullable()
    val digest = text("digest").nullable()
    val status = text("status")
    val reportedAt = instant("reported_at")

    override val primaryKey = PrimaryKey(deviceId, skillId)
}
