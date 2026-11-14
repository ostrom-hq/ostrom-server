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
import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.domain.MemberDecision
import run.ostrom.server.skills.domain.ValidatedArtifact
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asByteArray
import kotlin.uuid.Uuid

/**
 * [HooksRepository] on PostgreSQL (tables of [HooksMigrations]), every transaction confined to its organization
 * ([inOrg], row-level security). A write takes the organization's hook lock first (a transaction-scoped advisory
 * lock), so the organization's hook writes run one after the other, on any replica.
 *
 * @param driver The database.
 */
internal class HooksRepositoryImpl(private val driver: Driver) : HooksRepository {
    override suspend fun <T> read(orgId: Uuid, block: suspend HooksStore.() -> T): T =
        driver.inOrg(orgId) { PostgresHooksStore(this, orgId).block() }

    override suspend fun <T> write(orgId: Uuid, block: suspend HooksStore.() -> T): T = driver.inOrg(orgId) {
        fetchAll(Statement.create(LOCK).bind("org_id", orgId)).getOrThrow()
        PostgresHooksStore(this, orgId).block()
    }

    /** The SQL the repository shares with its tests. */
    companion object {
        /** Serializes an organization's hook writes within a transaction (released at its end). */
        const val LOCK: String =
            "SELECT pg_advisory_xact_lock(hashtextextended('hooks:' || CAST(:org_id AS TEXT), 0))"
    }
}

/** The statements of one transaction. */
@Suppress("TooManyFunctions") // One implementation of the module's one store.
private class PostgresHooksStore(private val tx: Transaction, private val orgId: Uuid) : HooksStore {
    override suspend fun hook(name: String): Hook? =
        rows("$HOOK_SELECT WHERE h.org_id = :org_id AND h.name = :name") { bind("name", name) }
            .firstOrNull()?.toHook()

    override suspend fun catalog(limit: Int): List<Hook> =
        rows("$HOOK_SELECT WHERE h.org_id = :org_id ORDER BY h.updated_at DESC, h.name LIMIT :limit") {
            bind("limit", limit)
        }.map { it.toHook() }

    override suspend fun countHooks(): Long =
        rows("SELECT COUNT(*) AS n FROM hooks_hooks WHERE org_id = :org_id") { this }.first().get("n").asLong()

    override suspend fun insertHook(hook: Hook) = update(
        """
        INSERT INTO hooks_hooks (id, org_id, name, pinned_version_id, all_members, created_at, updated_at)
        VALUES (:id, :org_id, :name, :pinned, :all, :created, :updated)
        """,
    ) { hookValues(hook).bind("name", hook.name).bind("created", hook.createdAt) }

    override suspend fun updateHook(hook: Hook) = update(
        """
        UPDATE hooks_hooks SET pinned_version_id = :pinned, all_members = :all, updated_at = :updated
        WHERE id = :id AND org_id = :org_id
        """,
    ) { hookValues(hook) }

    override suspend fun version(hookId: Uuid, label: String): HookVersion? = rows(
        "SELECT $VERSION_COLUMNS FROM hooks_versions WHERE org_id = :org_id AND hook_id = :hook AND version = :v",
    ) { bind("hook", hookId).bind("v", label) }.firstOrNull()?.toVersion()

    override suspend fun versions(hookId: Uuid, limit: Int): List<HookVersion> = rows(
        """
        SELECT $VERSION_COLUMNS FROM hooks_versions WHERE org_id = :org_id AND hook_id = :hook
        ORDER BY published_at DESC, version LIMIT :limit
        """,
    ) { bind("hook", hookId).bind("limit", limit) }.map { it.toVersion() }

    override suspend fun countVersions(hookId: Uuid): Long =
        rows("SELECT COUNT(*) AS n FROM hooks_versions WHERE org_id = :org_id AND hook_id = :hook") {
            bind("hook", hookId)
        }.first().get("n").asLong()

    override suspend fun insertVersion(version: HookVersion, artifact: ValidatedArtifact) {
        val definition = version.definition
        update(
            """
            INSERT INTO hooks_versions (id, org_id, hook_id, version, digest, events, matcher, command,
            timeout_seconds, size_bytes, file_count, published_by, published_at)
            VALUES (:id, :org_id, :hook, :v, :digest, :events, :matcher, :command, :timeout, :size, :count, :by, :at)
            """,
        ) {
            bind("id", version.id).bind("hook", version.hookId).bind("v", version.version)
                .bind("digest", version.digest).bind("events", definition.wireEvents.joinToString(","))
                .bind("matcher", definition.matcher).bind("command", definition.command)
                .bind("timeout", definition.timeoutSeconds).bind("size", version.sizeBytes)
                .bind("count", version.fileCount).bind("by", version.publishedBy).bind("at", version.publishedAt)
        }
        artifact.files.zip(artifact.entries).forEach { (file, entry) ->
            update(
                """
                INSERT INTO hooks_files (version_id, org_id, path, mode, size, sha256, content)
                VALUES (:version, :org_id, :path, :mode, :size, :sha, CAST(:content AS BYTEA))
                """,
            ) {
                bind("version", version.id).bind("path", entry.path).bind("mode", entry.mode.wire)
                    .bind("size", entry.size).bind("sha", entry.sha256).bind("content", file.content)
            }
        }
    }

    override suspend fun assignment(hookId: Uuid, memberId: String): HookAssignment? = rows(
        "SELECT id, hook_id, member_id, decision, updated_at FROM hooks_assignments WHERE org_id = :org_id " +
            "AND hook_id = :hook AND member_id = :member",
    ) { bind("hook", hookId).bind("member", memberId) }.firstOrNull()?.let { row ->
        HookAssignment(
            id = row.get("id").asUuid(),
            hookId = row.get("hook_id").asUuid(),
            memberId = row.get("member_id").asString(),
            decision = row.get("decision").asStringOrNull()?.let(::decision),
            updatedAt = row.get("updated_at").asInstant(),
        )
    }

    override suspend fun saveAssignment(assignment: HookAssignment, updatedBy: String) = update(
        """
        INSERT INTO hooks_assignments (id, org_id, hook_id, member_id, decision, updated_by, updated_at)
        VALUES (:id, :org_id, :hook, :member, :decision, :by, :at)
        ON CONFLICT (id) DO UPDATE SET decision = EXCLUDED.decision, updated_by = EXCLUDED.updated_by,
        updated_at = EXCLUDED.updated_at
        """,
    ) {
        bind("id", assignment.id).bind("hook", assignment.hookId).bind("member", assignment.memberId)
            .bind("decision", assignment.decision?.wire).bind("by", updatedBy).bind("at", assignment.updatedAt)
    }

    override suspend fun assignments(hookId: Uuid, limit: Int): List<MemberDecision> = rows(
        """
        SELECT member_id, decision, updated_at FROM hooks_assignments WHERE org_id = :org_id AND hook_id = :hook
        AND decision IS NOT NULL ORDER BY member_id LIMIT :limit
        """,
    ) { bind("hook", hookId).bind("limit", limit) }.map { row ->
        MemberDecision(
            row.get("member_id").asString(),
            decision(row.get("decision").asString()),
            row.get("updated_at").asInstant(),
        )
    }

    override suspend fun candidates(memberId: String, limit: Int): List<HookCandidate> = rows(
        """
        SELECT ${HOOK_COLUMNS.joinToString { "h.$it" }}, v.version AS pinned_label,
        ${VERSION_COLUMN_NAMES.joinToString { "v.$it AS v_$it" }}, a.decision
        FROM hooks_hooks h
        LEFT JOIN hooks_versions v ON v.org_id = h.org_id AND v.id = h.pinned_version_id
        LEFT JOIN hooks_assignments a ON a.org_id = h.org_id AND a.hook_id = h.id AND a.member_id = :member
        WHERE h.org_id = :org_id ORDER BY h.name LIMIT :limit
        """,
    ) { bind("member", memberId).bind("limit", limit) }.map { row ->
        val pinned = row.get("v_id").asUuidOrNull()?.let { row.toVersion(prefix = "v_") }
        HookCandidate(row.toHook(), pinned, row.get("decision").asStringOrNull()?.let(::decision))
    }

    override suspend fun files(versionId: Uuid): List<ArtifactFile> = rows(
        "SELECT path, mode, content FROM hooks_files WHERE org_id = :org_id AND version_id = :version ORDER BY path",
    ) { bind("version", versionId) }.map { row ->
        ArtifactFile(row.get("path").asString(), mode(row.get("mode").asString()), row.get("content").asByteArray())
    }

    private fun Statement.hookValues(hook: Hook): Statement = bind("id", hook.id)
        .bind("pinned", hook.pinnedVersionId).bind("all", hook.allMembers).bind("updated", hook.updatedAt)

    private suspend fun rows(sql: String, bind: Statement.() -> Statement): ResultSet =
        tx.fetchAll(Statement.create(sql.trimIndent()).bind("org_id", orgId).bind()).getOrThrow()

    private suspend fun update(sql: String, bind: Statement.() -> Statement) {
        tx.execute(Statement.create(sql.trimIndent()).bind("org_id", orgId).bind()).getOrThrow()
    }

    private fun ResultSet.Row.toHook() = Hook(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        name = get("name").asString(),
        pinnedVersionId = get("pinned_version_id").asUuidOrNull(),
        pinnedVersion = get("pinned_label").asStringOrNull(),
        allMembers = get("all_members").asBoolean(),
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
    )

    private fun ResultSet.Row.toVersion(prefix: String = "") = HookVersion(
        id = get("${prefix}id").asUuid(),
        hookId = get("${prefix}hook_id").asUuid(),
        version = get("${prefix}version").asString(),
        digest = get("${prefix}digest").asString(),
        definition = HookDefinition(
            // Stored values are written by this module only, from the enums.
            events = get("${prefix}events").asString().split(',').map { wire ->
                HookEvent.entries.first { it.wire == wire }
            }.toSet(),
            matcher = get("${prefix}matcher").asStringOrNull(),
            command = get("${prefix}command").asString(),
            timeoutSeconds = get("${prefix}timeout_seconds").asInt(),
        ),
        sizeBytes = get("${prefix}size_bytes").asLong(),
        fileCount = get("${prefix}file_count").asInt(),
        publishedBy = get("${prefix}published_by").asString(),
        publishedAt = get("${prefix}published_at").asInstant(),
    )

    // Stored values are written by this module only, from these enums.
    private fun decision(wire: String) = Decision.entries.first { it.wire == wire }

    private fun mode(wire: String) = FileMode.entries.first { it.wire == wire }
}

// The columns the store reads.
private val HOOK_COLUMNS =
    listOf("id", "org_id", "name", "pinned_version_id", "all_members", "created_at", "updated_at")
private val HOOK_SELECT = "SELECT ${HOOK_COLUMNS.joinToString { "h.$it" }}, p.version AS pinned_label " +
    "FROM hooks_hooks h LEFT JOIN hooks_versions p ON p.org_id = h.org_id AND p.id = h.pinned_version_id"
private val VERSION_COLUMN_NAMES = listOf(
    "id", "hook_id", "version", "digest", "events", "matcher", "command", "timeout_seconds", "size_bytes",
    "file_count", "published_by", "published_at",
)
private val VERSION_COLUMNS = VERSION_COLUMN_NAMES.joinToString()
