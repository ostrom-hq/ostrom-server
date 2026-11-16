// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.kernel.database.present
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
import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SkillsRepository] on the tables of [SkillsMigrations]: every transaction confined to its organization (`inOrg`,
 * row-level security) and every statement filtered on it. A write takes the organization's catalog lock first (a
 * transaction-scoped advisory lock, the `hashtextextended` of the former repository, so that both versions take turns
 * during a rollout), so the organization's catalog writes run one after the other, on any replica.
 *
 * @param database The serving role's pool.
 */
internal class SkillsRepositoryImpl(private val database: JdbcDatabase) : SkillsRepository {
    override suspend fun <T> read(orgId: Uuid, block: SkillsStore.() -> T): T =
        database.inOrg(orgId) { ExposedSkillsStore(orgId).block() }

    override suspend fun <T> write(orgId: Uuid, block: SkillsStore.() -> T): T = database.inOrg(orgId) {
        lockKeyUntilCommit(lockKey(orgId))
        ExposedSkillsStore(orgId).block()
    }
}

/** The statements of one transaction. */
@Suppress("TooManyFunctions") // One implementation of the module's one store.
private class ExposedSkillsStore(private val orgId: Uuid) : SkillsStore {
    override fun skill(name: String): Skill? =
        skillRead().where { (s.orgId eq orgId) and (s.name eq name) }.singleOrNull()?.toSkill()

    // Team skills first (at most SkillLimits.SKILLS), so personal skills never push one out of the page.
    override fun catalog(limit: Int): List<Skill> = skillRead()
        .where { s.orgId eq orgId }
        .orderBy(
            s.ownerId.isNotNull() to SortOrder.ASC,
            s.updatedAt to SortOrder.DESC,
            s.name to SortOrder.ASC,
        )
        .limit(limit)
        .map { it.toSkill() }

    override fun personal(ownerId: String, limit: Int): List<Skill> = skillRead()
        .where { (s.orgId eq orgId) and (s.ownerId eq ownerId) }
        .orderBy(s.updatedAt to SortOrder.DESC, s.name to SortOrder.ASC)
        .limit(limit)
        .map { it.toSkill() }

    override fun countTeamSkills(): Long =
        s.select(s.id).where { (s.orgId eq orgId) and s.ownerId.isNull() }.count()

    override fun countPersonalSkills(ownerId: String): Long =
        s.select(s.id).where { (s.orgId eq orgId) and (s.ownerId eq ownerId) }.count()

    override fun insertSkill(skill: Skill) {
        s.insert {
            it.skillValues(skill)
            it[id] = skill.id
            it[orgId] = this@ExposedSkillsStore.orgId
            it[name] = skill.name
            it[createdAt] = skill.createdAt
        }
    }

    override fun updateSkill(skill: Skill) {
        s.update({ (s.id eq skill.id) and (s.orgId eq orgId) }) { it.skillValues(skill) }
    }

    override fun version(skillId: Uuid, label: String): SkillVersion? = v.select(VERSION_COLUMNS)
        .where {
            (v[SkillsVersions.orgId] eq orgId) and (v[SkillsVersions.skillId] eq skillId) and
                (v[SkillsVersions.version] eq label)
        }
        .singleOrNull()
        ?.toVersion()

    override fun versions(skillId: Uuid, limit: Int): List<SkillVersion> = v.select(VERSION_COLUMNS)
        .where { (v[SkillsVersions.orgId] eq orgId) and (v[SkillsVersions.skillId] eq skillId) }
        .orderBy(v[SkillsVersions.publishedAt] to SortOrder.DESC, v[SkillsVersions.version] to SortOrder.ASC)
        .limit(limit)
        .map { it.toVersion() }

    override fun countVersions(skillId: Uuid): Long = SkillsVersions.select(SkillsVersions.id)
        .where { (SkillsVersions.orgId eq orgId) and (SkillsVersions.skillId eq skillId) }
        .count()

    override fun insertVersion(version: SkillVersion, artifact: ValidatedArtifact) {
        SkillsVersions.insert {
            it[id] = version.id
            it[orgId] = this@ExposedSkillsStore.orgId
            it[skillId] = version.skillId
            it[this.version] = version.version
            it[digest] = version.digest
            it[sizeBytes] = version.sizeBytes
            it[fileCount] = version.fileCount
            it[publishedBy] = version.publishedBy
            it[publishedAt] = version.publishedAt
        }
        // One batch: pgjdbc rewrites it into one multi-row INSERT.
        val rows = artifact.files.zip(artifact.entries)
        SkillsFiles.batchInsert(rows, shouldReturnGeneratedValues = false) { (file, entry) ->
            this[SkillsFiles.versionId] = version.id
            this[SkillsFiles.orgId] = orgId
            this[SkillsFiles.path] = entry.path
            this[SkillsFiles.mode] = entry.mode.wire
            this[SkillsFiles.size] = entry.size
            this[SkillsFiles.sha256] = entry.sha256
            this[SkillsFiles.content] = file.content
        }
    }

    override fun retireVersion(versionId: Uuid, at: Instant) {
        SkillsVersions.update({ (SkillsVersions.orgId eq orgId) and (SkillsVersions.id eq versionId) }) {
            it[retiredAt] = at
        }
    }

    override fun assignment(skillId: Uuid, memberId: String): Assignment? =
        a.select(a.id, a.skillId, a.memberId, a.decision, a.updatedAt)
            .where { (a.orgId eq orgId) and (a.skillId eq skillId) and (a.memberId eq memberId) }
            .singleOrNull()
            ?.let { row ->
                Assignment(
                    id = row[a.id],
                    skillId = row[a.skillId],
                    memberId = row[a.memberId],
                    decision = row[a.decision]?.let(::decision),
                    updatedAt = row[a.updatedAt],
                )
            }

    override fun saveAssignment(assignment: Assignment, updatedBy: String) {
        a.upsert(
            a.id,
            onUpdate = {
                it[a.decision] = insertValue(a.decision)
                it[a.updatedBy] = insertValue(a.updatedBy)
                it[a.updatedAt] = insertValue(a.updatedAt)
            },
        ) {
            it[id] = assignment.id
            it[orgId] = this@ExposedSkillsStore.orgId
            it[skillId] = assignment.skillId
            it[memberId] = assignment.memberId
            it[decision] = assignment.decision?.wire
            it[this.updatedBy] = updatedBy
            it[updatedAt] = assignment.updatedAt
        }
    }

    override fun assignments(skillId: Uuid, limit: Int): List<MemberDecision> =
        a.select(a.memberId, a.decision, a.updatedAt)
            .where { (a.orgId eq orgId) and (a.skillId eq skillId) and a.decision.isNotNull() }
            .orderBy(a.memberId)
            .limit(limit)
            .map { row ->
                // Not null by the filter: `decision IS NOT NULL`.
                MemberDecision(row[a.memberId], decision(row.present(a.decision, a.memberId)), row[a.updatedAt])
            }

    override fun candidates(memberId: String, limit: Int): List<Candidate> = s
        .join(v, JoinType.LEFT, additionalConstraint = {
            (v[SkillsVersions.orgId] eq s.orgId) and (v[SkillsVersions.id] eq s.pinnedVersionId)
        })
        .join(a, JoinType.LEFT, additionalConstraint = {
            (a.orgId eq s.orgId) and (a.skillId eq s.id) and (a.memberId eq memberId)
        })
        .select(SKILL_COLUMNS + VERSION_COLUMNS + a.decision)
        .where { (s.orgId eq orgId) and (s.ownerId.isNull() or (s.ownerId eq memberId)) }
        .orderBy(s.name)
        .limit(limit)
        .map { row ->
            val pinned = row.getOrNull(v[SkillsVersions.id])?.let { row.toVersion() }
            Candidate(row.toSkill(pinnedLabel = v), pinned, row[a.decision]?.let(::decision))
        }

    override fun files(versionId: Uuid): List<ArtifactFile> = SkillsFiles
        .select(SkillsFiles.path, SkillsFiles.mode, SkillsFiles.content)
        .where { (SkillsFiles.orgId eq orgId) and (SkillsFiles.versionId eq versionId) }
        .orderBy(SkillsFiles.path)
        .map { ArtifactFile(it[SkillsFiles.path], mode(it[SkillsFiles.mode]), it[SkillsFiles.content]) }

    override fun reportable(names: Set<String>, memberId: String): Map<String, Uuid> =
        s.select(s.id, s.name)
            .where {
                (s.orgId eq orgId) and (s.name inList names) and (s.ownerId.isNull() or (s.ownerId eq memberId))
            }
            .associate { it[s.name] to it[s.id] }

    override fun digests(skillIds: Collection<Uuid>, labels: Set<String>): Map<Pair<Uuid, String>, String> {
        if (labels.isEmpty()) return emptyMap()
        return SkillsVersions.select(SkillsVersions.skillId, SkillsVersions.version, SkillsVersions.digest)
            .where {
                (SkillsVersions.orgId eq orgId) and (SkillsVersions.skillId inList skillIds) and
                    (SkillsVersions.version inList labels)
            }
            .associate { (it[SkillsVersions.skillId] to it[SkillsVersions.version]) to it[SkillsVersions.digest] }
    }

    override fun saveInstallations(
        workstation: Workstation,
        rows: List<Pair<Uuid, InstallationReport>>,
        at: Instant,
    ) = rows.forEach { (skillId, report) ->
        i.upsert(
            i.deviceId,
            i.skillId,
            onUpdate = {
                it[i.deviceName] = insertValue(i.deviceName)
                it[i.version] = insertValue(i.version)
                it[i.digest] = insertValue(i.digest)
                it[i.status] = insertValue(i.status)
                it[i.reportedAt] = insertValue(i.reportedAt)
            },
        ) {
            it[orgId] = this@ExposedSkillsStore.orgId
            it[deviceId] = workstation.id
            it[deviceName] = workstation.name
            it[memberId] = workstation.memberId
            it[this.skillId] = skillId
            it[version] = report.version
            it[digest] = report.digest
            it[status] = report.status.wire
            it[reportedAt] = at
        }
    }

    override fun installations(memberId: String, limit: Int): List<Installation> = i
        .join(s, JoinType.INNER, additionalConstraint = { (s.orgId eq i.orgId) and (s.id eq i.skillId) })
        .select(i.deviceId, i.deviceName, s.name, i.version, i.digest, i.status, i.reportedAt)
        .where { (i.orgId eq orgId) and (i.memberId eq memberId) }
        .orderBy(i.reportedAt to SortOrder.DESC, s.name to SortOrder.ASC)
        .limit(limit)
        .map { row ->
            Installation(
                deviceId = row[i.deviceId],
                deviceName = row[i.deviceName],
                name = row[s.name],
                version = row[i.version],
                digest = row[i.digest],
                status = status(row[i.status]),
                reportedAt = row[i.reportedAt],
            )
        }

    // The skills with their pinned version's label, joined on the organization too.
    private fun skillRead(): Query = s
        .join(p, JoinType.LEFT, additionalConstraint = {
            (p[SkillsVersions.orgId] eq s.orgId) and (p[SkillsVersions.id] eq s.pinnedVersionId)
        })
        .select(SKILL_COLUMNS + p[SkillsVersions.version])

    // The columns a change writes; the id, the name and the creation are the insert's only.
    private fun UpdateBuilder<*>.skillValues(skill: Skill) {
        this[s.ownerId] = skill.ownerId
        this[s.pinnedVersionId] = skill.pinnedVersionId
        this[s.allMembers] = skill.allMembers
        this[s.retiredAt] = skill.retiredAt
        this[s.updatedAt] = skill.updatedAt
    }

    private fun ResultRow.toSkill(pinnedLabel: Alias<SkillsVersions> = p) = Skill(
        id = this[s.id],
        orgId = this[s.orgId],
        name = this[s.name],
        ownerId = this[s.ownerId],
        pinnedVersionId = this[s.pinnedVersionId],
        pinnedVersion = getOrNull(pinnedLabel[SkillsVersions.version]),
        allMembers = this[s.allMembers],
        retiredAt = this[s.retiredAt],
        createdAt = this[s.createdAt],
        updatedAt = this[s.updatedAt],
    )

    private fun ResultRow.toVersion() = SkillVersion(
        id = this[v[SkillsVersions.id]],
        skillId = this[v[SkillsVersions.skillId]],
        version = this[v[SkillsVersions.version]],
        digest = this[v[SkillsVersions.digest]],
        sizeBytes = this[v[SkillsVersions.sizeBytes]],
        fileCount = this[v[SkillsVersions.fileCount]],
        publishedBy = this[v[SkillsVersions.publishedBy]],
        publishedAt = this[v[SkillsVersions.publishedAt]],
        retiredAt = this[v[SkillsVersions.retiredAt]],
    )

    // Stored values are written by this module only, from these enums.
    private fun decision(wire: String) = Decision.entries.first { it.wire == wire }

    private fun mode(wire: String) = FileMode.entries.first { it.wire == wire }

    private fun status(wire: String) = InstallationStatus.entries.first { it.wire == wire }
}

// The advisory lock's key, `'skills:' || CAST(org_id AS TEXT)` as the former repository hashed it.
private fun lockKey(orgId: Uuid): String = "skills:$orgId"

private val s = SkillsSkills
private val a = SkillsAssignments
private val i = SkillsInstallations

/** The pinned version of a skill in a catalog read, for its label. */
private val p = SkillsVersions.alias("p")

/** The versions a store reads whole: a skill's, or a candidate's pinned one. */
private val v = SkillsVersions.alias("v")

private val SKILL_COLUMNS = listOf(
    s.id, s.orgId, s.name, s.ownerId, s.pinnedVersionId, s.allMembers, s.retiredAt, s.createdAt, s.updatedAt,
)

private val VERSION_COLUMNS = with(SkillsVersions) {
    listOf(id, skillId, version, digest, sizeBytes, fileCount, publishedBy, publishedAt, retiredAt).map { v[it] }
}
