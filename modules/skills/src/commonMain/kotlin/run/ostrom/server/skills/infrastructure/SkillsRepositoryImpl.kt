// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.infrastructure

import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.Assignment
import run.ostrom.server.skills.domain.Candidate
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.domain.Installation
import run.ostrom.server.skills.domain.InstallationReport
import run.ostrom.server.skills.domain.InstallationStatus
import run.ostrom.server.skills.domain.MemberDecision
import run.ostrom.server.skills.domain.Skill
import run.ostrom.server.skills.domain.SkillVersion
import run.ostrom.server.skills.domain.SkillsRepository
import run.ostrom.server.skills.domain.SkillsStore
import run.ostrom.server.skills.domain.ValidatedArtifact
import run.ostrom.server.skills.domain.Workstation
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asByteArray
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SkillsRepository] on PostgreSQL (tables of [SkillsMigrations]), every transaction confined to its organization
 * ([inOrg], row-level security). A write takes the organization's catalog lock first (a transaction-scoped advisory
 * lock), so the organization's catalog writes run one after the other, on any replica.
 *
 * @param driver The database.
 */
internal class SkillsRepositoryImpl(private val driver: Driver) : SkillsRepository {
    override suspend fun <T> read(orgId: Uuid, block: suspend SkillsStore.() -> T): T =
        driver.inOrg(orgId) { PostgresSkillsStore(this, orgId).block() }

    override suspend fun <T> write(orgId: Uuid, block: suspend SkillsStore.() -> T): T = driver.inOrg(orgId) {
        fetchAll(Statement.create(LOCK).bind("org_id", orgId)).getOrThrow()
        PostgresSkillsStore(this, orgId).block()
    }

    /** The SQL the repository shares with its tests. */
    companion object {
        /** Serializes an organization's catalog writes within a transaction (released at its end). */
        const val LOCK: String =
            "SELECT pg_advisory_xact_lock(hashtextextended('skills:' || CAST(:org_id AS TEXT), 0))"
    }
}

/** The statements of one transaction. */
@Suppress("TooManyFunctions") // One implementation of the module's one store.
private class PostgresSkillsStore(private val tx: Transaction, private val orgId: Uuid) : SkillsStore {
    override suspend fun skill(name: String): Skill? =
        rows("$SKILL_SELECT WHERE s.org_id = :org_id AND s.name = :name") { bind("name", name) }
            .firstOrNull()?.toSkill()

    override suspend fun catalog(limit: Int): List<Skill> =
        // Team skills first (at most SkillLimits.SKILLS), so personal skills never push one out of the page.
        rows(
            "$SKILL_SELECT WHERE s.org_id = :org_id " +
                "ORDER BY s.owner_id IS NOT NULL, s.updated_at DESC, s.name LIMIT :limit",
        ) { bind("limit", limit) }.map { it.toSkill() }

    override suspend fun personal(ownerId: String, limit: Int): List<Skill> = rows(
        "$SKILL_SELECT WHERE s.org_id = :org_id AND s.owner_id = :owner " +
            "ORDER BY s.updated_at DESC, s.name LIMIT :limit",
    ) { bind("owner", ownerId).bind("limit", limit) }.map { it.toSkill() }

    override suspend fun countTeamSkills(): Long =
        rows("SELECT COUNT(*) AS n FROM skills_skills WHERE org_id = :org_id AND owner_id IS NULL") { this }
            .first().get("n").asLong()

    override suspend fun countPersonalSkills(ownerId: String): Long =
        rows("SELECT COUNT(*) AS n FROM skills_skills WHERE org_id = :org_id AND owner_id = :owner") {
            bind("owner", ownerId)
        }.first().get("n").asLong()

    override suspend fun insertSkill(skill: Skill) = update(
        """
        INSERT INTO skills_skills (id, org_id, name, owner_id, pinned_version_id, all_members, retired_at, created_at,
        updated_at) VALUES (:id, :org_id, :name, :owner, :pinned, :all, :retired, :created, :updated)
        """,
    ) { skillValues(skill).bind("name", skill.name).bind("created", skill.createdAt) }

    override suspend fun updateSkill(skill: Skill) = update(
        """
        UPDATE skills_skills SET owner_id = :owner, pinned_version_id = :pinned, all_members = :all,
        retired_at = :retired, updated_at = :updated WHERE id = :id AND org_id = :org_id
        """,
    ) { skillValues(skill) }

    override suspend fun version(skillId: Uuid, label: String): SkillVersion? = rows(
        "SELECT $VERSION_COLUMNS FROM skills_versions WHERE org_id = :org_id AND skill_id = :skill AND version = :v",
    ) { bind("skill", skillId).bind("v", label) }.firstOrNull()?.toVersion()

    override suspend fun versions(skillId: Uuid, limit: Int): List<SkillVersion> = rows(
        """
        SELECT $VERSION_COLUMNS FROM skills_versions WHERE org_id = :org_id AND skill_id = :skill
        ORDER BY published_at DESC, version LIMIT :limit
        """,
    ) { bind("skill", skillId).bind("limit", limit) }.map { it.toVersion() }

    override suspend fun countVersions(skillId: Uuid): Long =
        rows("SELECT COUNT(*) AS n FROM skills_versions WHERE org_id = :org_id AND skill_id = :skill") {
            bind("skill", skillId)
        }.first().get("n").asLong()

    override suspend fun insertVersion(version: SkillVersion, artifact: ValidatedArtifact) {
        update(
            """
            INSERT INTO skills_versions (id, org_id, skill_id, version, digest, size_bytes, file_count, published_by,
            published_at, retired_at) VALUES (:id, :org_id, :skill, :v, :digest, :size, :count, :by, :at, NULL)
            """,
        ) {
            bind("id", version.id).bind("skill", version.skillId).bind("v", version.version)
                .bind("digest", version.digest).bind("size", version.sizeBytes).bind("count", version.fileCount)
                .bind("by", version.publishedBy).bind("at", version.publishedAt)
        }
        artifact.files.zip(artifact.entries).forEach { (file, entry) ->
            update(
                """
                INSERT INTO skills_files (version_id, org_id, path, mode, size, sha256, content)
                VALUES (:version, :org_id, :path, :mode, :size, :sha, CAST(:content AS BYTEA))
                """,
            ) {
                bind("version", version.id).bind("path", entry.path).bind("mode", entry.mode.wire)
                    .bind("size", entry.size).bind("sha", entry.sha256).bind("content", file.content)
            }
        }
    }

    override suspend fun retireVersion(versionId: Uuid, at: Instant) =
        update("UPDATE skills_versions SET retired_at = :at WHERE org_id = :org_id AND id = :id") {
            bind("at", at).bind("id", versionId)
        }

    override suspend fun assignment(skillId: Uuid, memberId: String): Assignment? = rows(
        "SELECT $ASSIGNMENT_COLUMNS FROM skills_assignments WHERE org_id = :org_id AND skill_id = :skill AND " +
            "member_id = :member",
    ) { bind("skill", skillId).bind("member", memberId) }.firstOrNull()?.toAssignment()

    override suspend fun saveAssignment(assignment: Assignment, updatedBy: String) = update(
        """
        INSERT INTO skills_assignments (id, org_id, skill_id, member_id, decision, updated_by, updated_at)
        VALUES (:id, :org_id, :skill, :member, :decision, :by, :at)
        ON CONFLICT (id) DO UPDATE SET decision = EXCLUDED.decision, updated_by = EXCLUDED.updated_by,
        updated_at = EXCLUDED.updated_at
        """,
    ) {
        bind("id", assignment.id).bind("skill", assignment.skillId).bind("member", assignment.memberId)
            .bind("decision", assignment.decision?.wire).bind("by", updatedBy).bind("at", assignment.updatedAt)
    }

    override suspend fun assignments(skillId: Uuid, limit: Int): List<MemberDecision> = rows(
        """
        SELECT member_id, decision, updated_at FROM skills_assignments WHERE org_id = :org_id AND skill_id = :skill
        AND decision IS NOT NULL ORDER BY member_id LIMIT :limit
        """,
    ) { bind("skill", skillId).bind("limit", limit) }.map { row ->
        MemberDecision(
            row.get("member_id").asString(),
            decision(row.get("decision").asString()),
            row.get("updated_at").asInstant(),
        )
    }

    override suspend fun candidates(memberId: String, limit: Int): List<Candidate> = rows(
        """
        SELECT ${SKILL_COLUMNS.joinToString { "s.$it" }}, v.version AS pinned_label,
        ${VERSION_COLUMN_NAMES.joinToString { "v.$it AS v_$it" }}, a.decision
        FROM skills_skills s
        LEFT JOIN skills_versions v ON v.org_id = s.org_id AND v.id = s.pinned_version_id
        LEFT JOIN skills_assignments a ON a.org_id = s.org_id AND a.skill_id = s.id AND a.member_id = :member
        WHERE s.org_id = :org_id AND (s.owner_id IS NULL OR s.owner_id = :member) ORDER BY s.name LIMIT :limit
        """,
    ) { bind("member", memberId).bind("limit", limit) }.map { row ->
        val pinned = row.get("v_id").asUuidOrNull()?.let { row.toVersion(prefix = "v_") }
        Candidate(row.toSkill(), pinned, row.get("decision").asStringOrNull()?.let(::decision))
    }

    override suspend fun files(versionId: Uuid): List<ArtifactFile> = rows(
        "SELECT path, mode, content FROM skills_files WHERE org_id = :org_id AND version_id = :version ORDER BY path",
    ) { bind("version", versionId) }.map { row ->
        ArtifactFile(row.get("path").asString(), mode(row.get("mode").asString()), row.get("content").asByteArray())
    }

    override suspend fun reportable(names: Set<String>, memberId: String): Map<String, Uuid> = rows(
        """
        SELECT id, name FROM skills_skills WHERE org_id = :org_id AND name = ANY(string_to_array(:names, ','))
        AND (owner_id IS NULL OR owner_id = :member)
        """,
    ) { bind("names", names.joinToString(",")).bind("member", memberId) }
        .associate { it.get("name").asString() to it.get("id").asUuid() }

    override suspend fun digests(skillIds: Collection<Uuid>, labels: Set<String>): Map<Pair<Uuid, String>, String> {
        if (labels.isEmpty()) return emptyMap()
        return rows(
            """
            SELECT skill_id, version, digest FROM skills_versions WHERE org_id = :org_id
            AND skill_id = ANY(CAST(string_to_array(:skills, ',') AS UUID[]))
            AND version = ANY(string_to_array(:labels, ','))
            """,
        ) { bind("skills", skillIds.joinToString(",")).bind("labels", labels.joinToString(",")) }
            .associate { (it.get("skill_id").asUuid() to it.get("version").asString()) to it.get("digest").asString() }
    }

    override suspend fun saveInstallations(
        workstation: Workstation,
        rows: List<Pair<Uuid, InstallationReport>>,
        at: Instant,
    ) = rows.forEach { (skillId, report) ->
        update(
            """
            INSERT INTO skills_installations (org_id, device_id, device_name, member_id, skill_id, version, digest,
            status, reported_at) VALUES (:org_id, :device, :device_name, :member, :skill, :v, :digest, :status, :at)
            ON CONFLICT (device_id, skill_id) DO UPDATE SET device_name = EXCLUDED.device_name,
            version = EXCLUDED.version, digest = EXCLUDED.digest, status = EXCLUDED.status,
            reported_at = EXCLUDED.reported_at
            """,
        ) {
            bind("device", workstation.id).bind("device_name", workstation.name).bind("member", workstation.memberId)
                .bind("skill", skillId).bind("v", report.version).bind("digest", report.digest)
                .bind("status", report.status.wire).bind("at", at)
        }
    }

    override suspend fun installations(memberId: String, limit: Int): List<Installation> = rows(
        """
        SELECT i.device_id, i.device_name, s.name, i.version, i.digest, i.status, i.reported_at
        FROM skills_installations i JOIN skills_skills s ON s.org_id = i.org_id AND s.id = i.skill_id
        WHERE i.org_id = :org_id AND i.member_id = :member ORDER BY i.reported_at DESC, s.name LIMIT :limit
        """,
    ) { bind("member", memberId).bind("limit", limit) }.map { row ->
        Installation(
            deviceId = row.get("device_id").asString(),
            deviceName = row.get("device_name").asString(),
            name = row.get("name").asString(),
            version = row.get("version").asStringOrNull(),
            digest = row.get("digest").asStringOrNull(),
            status = status(row.get("status").asString()),
            reportedAt = row.get("reported_at").asInstant(),
        )
    }

    private fun Statement.skillValues(skill: Skill): Statement = bind("id", skill.id)
        .bind("owner", skill.ownerId).bind("pinned", skill.pinnedVersionId).bind("all", skill.allMembers)
        .bind("retired", skill.retiredAt).bind("updated", skill.updatedAt)

    private suspend fun rows(sql: String, bind: Statement.() -> Statement): ResultSet =
        tx.fetchAll(Statement.create(sql.trimIndent()).bind("org_id", orgId).bind()).getOrThrow()

    private suspend fun update(sql: String, bind: Statement.() -> Statement) {
        tx.execute(Statement.create(sql.trimIndent()).bind("org_id", orgId).bind()).getOrThrow()
    }

    private fun ResultSet.Row.toSkill() = Skill(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        name = get("name").asString(),
        ownerId = get("owner_id").asStringOrNull(),
        pinnedVersionId = get("pinned_version_id").asUuidOrNull(),
        pinnedVersion = get("pinned_label").asStringOrNull(),
        allMembers = get("all_members").asBoolean(),
        retiredAt = get("retired_at").asInstantOrNull(),
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
    )

    private fun ResultSet.Row.toVersion(prefix: String = "") = SkillVersion(
        id = get("${prefix}id").asUuid(),
        skillId = get("${prefix}skill_id").asUuid(),
        version = get("${prefix}version").asString(),
        digest = get("${prefix}digest").asString(),
        sizeBytes = get("${prefix}size_bytes").asLong(),
        fileCount = get("${prefix}file_count").asInt(),
        publishedBy = get("${prefix}published_by").asString(),
        publishedAt = get("${prefix}published_at").asInstant(),
        retiredAt = get("${prefix}retired_at").asInstantOrNull(),
    )

    private fun ResultSet.Row.toAssignment() = Assignment(
        id = get("id").asUuid(),
        skillId = get("skill_id").asUuid(),
        memberId = get("member_id").asString(),
        decision = get("decision").asStringOrNull()?.let(::decision),
        updatedAt = get("updated_at").asInstant(),
    )

    // Stored values are written by this module only, from these enums.
    private fun decision(wire: String) = Decision.entries.first { it.wire == wire }

    private fun mode(wire: String) = FileMode.entries.first { it.wire == wire }

    private fun status(wire: String) = InstallationStatus.entries.first { it.wire == wire }
}

private val SKILL_COLUMNS = listOf(
    "id", "org_id", "name", "owner_id", "pinned_version_id", "all_members", "retired_at", "created_at",
    "updated_at",
)
private val SKILL_SELECT = "SELECT ${SKILL_COLUMNS.joinToString { "s.$it" }}, p.version AS pinned_label " +
    "FROM skills_skills s LEFT JOIN skills_versions p ON p.org_id = s.org_id AND p.id = s.pinned_version_id"
private val VERSION_COLUMN_NAMES = listOf(
    "id", "skill_id", "version", "digest", "size_bytes", "file_count", "published_by", "published_at",
    "retired_at",
)
private val VERSION_COLUMNS = VERSION_COLUMN_NAMES.joinToString()
private const val ASSIGNMENT_COLUMNS = "id, skill_id, member_id, decision, updated_at"
