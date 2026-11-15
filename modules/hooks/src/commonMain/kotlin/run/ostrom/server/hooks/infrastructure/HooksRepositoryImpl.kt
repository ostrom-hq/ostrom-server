// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.infrastructure

import run.ostrom.server.hooks.domain.Hook
import run.ostrom.server.hooks.domain.HookAssignment
import run.ostrom.server.hooks.domain.HookCandidate
import run.ostrom.server.hooks.domain.HookDefinition
import run.ostrom.server.hooks.domain.HookEvent
import run.ostrom.server.hooks.domain.HookVersion
import run.ostrom.server.hooks.domain.HooksRepository
import run.ostrom.server.hooks.domain.HooksStore
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.kernel.database.present
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.domain.MemberDecision
import run.ostrom.server.skills.domain.ValidatedArtifact
import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.Uuid

/**
 * [HooksRepository] on the tables of [HooksMigrations]: every transaction confined to its organization (`inOrg`,
 * row-level security) and every statement filtered on it. A write takes the organization's hook lock first (a
 * transaction-scoped advisory lock, the `hashtextextended` of the sqlx4k repository, so that both versions take turns
 * during a rollout), so the organization's hook writes run one after the other, on any replica.
 *
 * @param database The serving role's pool.
 */
internal class HooksRepositoryImpl(private val database: JdbcDatabase) : HooksRepository {
    override suspend fun <T> read(orgId: Uuid, block: HooksStore.() -> T): T =
        database.inOrg(orgId) { ExposedHooksStore(orgId).block() }

    override suspend fun <T> write(orgId: Uuid, block: HooksStore.() -> T): T = database.inOrg(orgId) {
        lockKeyUntilCommit(lockKey(orgId))
        ExposedHooksStore(orgId).block()
    }
}

/** The statements of one transaction. */
@Suppress("TooManyFunctions") // One implementation of the module's one store.
private class ExposedHooksStore(private val orgId: Uuid) : HooksStore {
    override fun hook(name: String): Hook? =
        hookRead().where { (h.orgId eq orgId) and (h.name eq name) }.singleOrNull()?.toHook()

    override fun catalog(limit: Int): List<Hook> = hookRead()
        .where { h.orgId eq orgId }
        .orderBy(h.updatedAt to SortOrder.DESC, h.name to SortOrder.ASC)
        .limit(limit)
        .map { it.toHook() }

    override fun countHooks(): Long = h.select(h.id).where { h.orgId eq orgId }.count()

    override fun insertHook(hook: Hook) {
        h.insert {
            it.hookValues(hook)
            it[orgId] = this@ExposedHooksStore.orgId
            it[name] = hook.name
            it[createdAt] = hook.createdAt
        }
    }

    override fun updateHook(hook: Hook) {
        h.update({ (h.id eq hook.id) and (h.orgId eq orgId) }) { it.hookValues(hook) }
    }

    override fun version(hookId: Uuid, label: String): HookVersion? = v.select(VERSION_COLUMNS)
        .where {
            (v[HooksVersions.orgId] eq orgId) and (v[HooksVersions.hookId] eq hookId) and
                (v[HooksVersions.version] eq label)
        }
        .singleOrNull()
        ?.toVersion()

    override fun versions(hookId: Uuid, limit: Int): List<HookVersion> = v.select(VERSION_COLUMNS)
        .where { (v[HooksVersions.orgId] eq orgId) and (v[HooksVersions.hookId] eq hookId) }
        .orderBy(v[HooksVersions.publishedAt] to SortOrder.DESC, v[HooksVersions.version] to SortOrder.ASC)
        .limit(limit)
        .map { it.toVersion() }

    override fun countVersions(hookId: Uuid): Long = HooksVersions.select(HooksVersions.id)
        .where { (HooksVersions.orgId eq orgId) and (HooksVersions.hookId eq hookId) }
        .count()

    override fun insertVersion(version: HookVersion, artifact: ValidatedArtifact) {
        val definition = version.definition
        HooksVersions.insert {
            it[id] = version.id
            it[orgId] = this@ExposedHooksStore.orgId
            it[hookId] = version.hookId
            it[this.version] = version.version
            it[digest] = version.digest
            it[events] = definition.wireEvents.joinToString(",")
            it[matcher] = definition.matcher
            it[command] = definition.command
            it[timeoutSeconds] = definition.timeoutSeconds
            it[sizeBytes] = version.sizeBytes
            it[fileCount] = version.fileCount
            it[publishedBy] = version.publishedBy
            it[publishedAt] = version.publishedAt
        }
        // One batch: pgjdbc rewrites it into one multi-row INSERT.
        val rows = artifact.files.zip(artifact.entries)
        HooksFiles.batchInsert(rows, shouldReturnGeneratedValues = false) { (file, entry) ->
            this[HooksFiles.versionId] = version.id
            this[HooksFiles.orgId] = orgId
            this[HooksFiles.path] = entry.path
            this[HooksFiles.mode] = entry.mode.wire
            this[HooksFiles.size] = entry.size
            this[HooksFiles.sha256] = entry.sha256
            this[HooksFiles.content] = file.content
        }
    }

    override fun assignment(hookId: Uuid, memberId: String): HookAssignment? =
        a.select(a.id, a.hookId, a.memberId, a.decision, a.updatedAt)
            .where { (a.orgId eq orgId) and (a.hookId eq hookId) and (a.memberId eq memberId) }
            .singleOrNull()
            ?.let { row ->
                HookAssignment(
                    id = row[a.id],
                    hookId = row[a.hookId],
                    memberId = row[a.memberId],
                    decision = row[a.decision]?.let(::decision),
                    updatedAt = row[a.updatedAt],
                )
            }

    override fun saveAssignment(assignment: HookAssignment, updatedBy: String) {
        a.upsert(
            a.id,
            onUpdate = {
                it[a.decision] = insertValue(a.decision)
                it[a.updatedBy] = insertValue(a.updatedBy)
                it[a.updatedAt] = insertValue(a.updatedAt)
            },
        ) {
            it[id] = assignment.id
            it[orgId] = this@ExposedHooksStore.orgId
            it[hookId] = assignment.hookId
            it[memberId] = assignment.memberId
            it[decision] = assignment.decision?.wire
            it[this.updatedBy] = updatedBy
            it[updatedAt] = assignment.updatedAt
        }
    }

    override fun assignments(hookId: Uuid, limit: Int): List<MemberDecision> =
        a.select(a.memberId, a.decision, a.updatedAt)
            .where { (a.orgId eq orgId) and (a.hookId eq hookId) and a.decision.isNotNull() }
            .orderBy(a.memberId)
            .limit(limit)
            .map { row ->
                // Not null by the filter: `decision IS NOT NULL`.
                MemberDecision(row[a.memberId], decision(row.present(a.decision, a.memberId)), row[a.updatedAt])
            }

    override fun candidates(memberId: String, limit: Int): List<HookCandidate> = h
        .join(v, JoinType.LEFT, additionalConstraint = {
            (v[HooksVersions.orgId] eq h.orgId) and (v[HooksVersions.id] eq h.pinnedVersionId)
        })
        .join(a, JoinType.LEFT, additionalConstraint = {
            (a.orgId eq h.orgId) and (a.hookId eq h.id) and (a.memberId eq memberId)
        })
        .select(HOOK_COLUMNS + VERSION_COLUMNS + a.decision)
        .where { h.orgId eq orgId }
        .orderBy(h.name)
        .limit(limit)
        .map { row ->
            val pinned = row.getOrNull(v[HooksVersions.id])?.let { row.toVersion() }
            HookCandidate(row.toHook(pinnedLabel = v), pinned, row[a.decision]?.let(::decision))
        }

    override fun files(versionId: Uuid): List<ArtifactFile> = HooksFiles
        .select(HooksFiles.path, HooksFiles.mode, HooksFiles.content)
        .where { (HooksFiles.orgId eq orgId) and (HooksFiles.versionId eq versionId) }
        .orderBy(HooksFiles.path)
        .map { ArtifactFile(it[HooksFiles.path], mode(it[HooksFiles.mode]), it[HooksFiles.content]) }

    // The hooks with their pinned version's label, joined on the organization too.
    private fun hookRead(): Query = h
        .join(p, JoinType.LEFT, additionalConstraint = {
            (p[HooksVersions.orgId] eq h.orgId) and (p[HooksVersions.id] eq h.pinnedVersionId)
        })
        .select(HOOK_COLUMNS + p[HooksVersions.version])

    private fun UpdateBuilder<*>.hookValues(hook: Hook) {
        this[h.id] = hook.id
        this[h.pinnedVersionId] = hook.pinnedVersionId
        this[h.allMembers] = hook.allMembers
        this[h.updatedAt] = hook.updatedAt
    }

    private fun ResultRow.toHook(pinnedLabel: Alias<HooksVersions> = p) = Hook(
        id = this[h.id],
        orgId = this[h.orgId],
        name = this[h.name],
        pinnedVersionId = this[h.pinnedVersionId],
        pinnedVersion = getOrNull(pinnedLabel[HooksVersions.version]),
        allMembers = this[h.allMembers],
        createdAt = this[h.createdAt],
        updatedAt = this[h.updatedAt],
    )

    private fun ResultRow.toVersion() = HookVersion(
        id = this[v[HooksVersions.id]],
        hookId = this[v[HooksVersions.hookId]],
        version = this[v[HooksVersions.version]],
        digest = this[v[HooksVersions.digest]],
        definition = HookDefinition(
            // Stored values are written by this module only, from the enums.
            events = this[v[HooksVersions.events]].split(',').map { wire ->
                HookEvent.entries.first { it.wire == wire }
            }.toSet(),
            matcher = this[v[HooksVersions.matcher]],
            command = this[v[HooksVersions.command]],
            timeoutSeconds = this[v[HooksVersions.timeoutSeconds]],
        ),
        sizeBytes = this[v[HooksVersions.sizeBytes]],
        fileCount = this[v[HooksVersions.fileCount]],
        publishedBy = this[v[HooksVersions.publishedBy]],
        publishedAt = this[v[HooksVersions.publishedAt]],
    )

    // Stored values are written by this module only, from these enums.
    private fun decision(wire: String) = Decision.entries.first { it.wire == wire }

    private fun mode(wire: String) = FileMode.entries.first { it.wire == wire }
}

// The advisory lock's key, `'hooks:' || CAST(org_id AS TEXT)` as the sqlx4k repository hashed it.
private fun lockKey(orgId: Uuid): String = "hooks:$orgId"

private val h = HooksHooks
private val a = HooksAssignments

/** The pinned version of a hook in a catalog read, for its label. */
private val p = HooksVersions.alias("p")

/** The versions a store reads whole: a hook's, or a candidate's pinned one. */
private val v = HooksVersions.alias("v")

private val HOOK_COLUMNS = listOf(h.id, h.orgId, h.name, h.pinnedVersionId, h.allMembers, h.createdAt, h.updatedAt)

private val VERSION_COLUMNS = with(HooksVersions) {
    listOf(
        id, hookId, version, digest, events, matcher, command, timeoutSeconds, sizeBytes, fileCount, publishedBy,
        publishedAt,
    ).map { v[it] }
}
