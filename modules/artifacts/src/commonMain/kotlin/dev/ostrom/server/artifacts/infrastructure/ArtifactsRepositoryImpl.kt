package dev.ostrom.server.artifacts.infrastructure

import dev.ostrom.server.artifacts.domain.Artifact
import dev.ostrom.server.artifacts.domain.ArtifactAccess
import dev.ostrom.server.artifacts.domain.ArtifactCaller
import dev.ostrom.server.artifacts.domain.ArtifactFile
import dev.ostrom.server.artifacts.domain.ArtifactVersion
import dev.ostrom.server.artifacts.domain.ArtifactsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactsRepository] on PostgreSQL (tables of [ArtifactsMigrations]). A version's file list is JSON in
 * `artifacts_versions.files`; each distinct content is one `artifacts_blobs` row per artifact, so republishing an
 * unchanged file stores nothing.
 *
 * @param driver The database.
 */
@Suppress("TooManyFunctions") // One implementation of the module's one port.
internal class ArtifactsRepositoryImpl(private val driver: Driver) : ArtifactsRepository {
    override suspend fun create(artifact: Artifact, version: ArtifactVersion, blobs: Map<String, ByteArray>) {
        driver.transaction {
            execute(
                Statement.create(
                    """
                    INSERT INTO artifacts_artifacts ($COLUMNS) VALUES (:id, :org_id, :owner_id, :title, :access,
                    :latest_version, :link_slug, :created_at, :updated_at)
                    """,
                ).bind("id", artifact.id)
                    .bind("org_id", artifact.orgId)
                    .bind("owner_id", artifact.ownerId)
                    .bind("title", artifact.title)
                    .bind("access", artifact.access.wire)
                    .bind("latest_version", artifact.latestVersion)
                    .bind("link_slug", artifact.linkSlug)
                    .bind("created_at", artifact.createdAt)
                    .bind("updated_at", artifact.updatedAt),
            ).getOrThrow()
            insertContent(version, blobs)
        }
    }

    override suspend fun addVersion(
        orgId: Uuid,
        version: ArtifactVersion,
        blobs: Map<String, ByteArray>,
        now: Instant,
    ): Artifact? = driver.transaction {
        val updated = fetchAll(
            Statement.create(
                """
                UPDATE artifacts_artifacts SET latest_version = :number, updated_at = :now WHERE id = :id AND
                org_id = :org_id AND latest_version = :previous RETURNING $COLUMNS
                """,
            ).bind("number", version.number)
                .bind("now", now)
                .bind("id", version.artifactId)
                .bind("org_id", orgId)
                .bind("previous", version.number - 1),
        ).getOrThrow().firstOrNull()?.toArtifact()
        if (updated != null) insertContent(version, blobs)
        updated
    }

    override suspend fun updateSettings(artifact: Artifact) {
        driver.execute(
            Statement.create(
                """
                UPDATE artifacts_artifacts SET title = :title, access = :access, link_slug = :link_slug, updated_at =
                :updated_at WHERE id = :id AND org_id = :org_id
                """,
            ).bind("title", artifact.title)
                .bind("access", artifact.access.wire)
                .bind("link_slug", artifact.linkSlug)
                .bind("updated_at", artifact.updatedAt)
                .bind("id", artifact.id)
                .bind("org_id", artifact.orgId),
        ).getOrThrow()
    }

    override suspend fun get(orgId: Uuid, id: Uuid): Artifact? = driver.fetchAll(
        Statement.create("""SELECT $COLUMNS FROM artifacts_artifacts WHERE id = :id AND org_id = :org_id""")
            .bind("id", id).bind("org_id", orgId),
    ).getOrThrow().firstOrNull()?.toArtifact()

    override suspend fun getById(id: Uuid): Artifact? = driver.fetchAll(
        Statement.create("""SELECT $COLUMNS FROM artifacts_artifacts WHERE id = :id""").bind("id", id),
    ).getOrThrow().firstOrNull()?.toArtifact()

    override suspend fun getByLinkSlug(slug: String): Artifact? = driver.fetchAll(
        Statement.create("""SELECT $COLUMNS FROM artifacts_artifacts WHERE link_slug = :slug""").bind("slug", slug),
    ).getOrThrow().firstOrNull()?.toArtifact()

    override suspend fun listReadable(caller: ArtifactCaller, before: Uuid?, limit: Int): List<Artifact> {
        val cursor = if (before == null) {
            ""
        } else {
            """ AND (a.updated_at, a.id) <
                (SELECT updated_at, id FROM artifacts_artifacts WHERE id = :before AND org_id = :org_id)"""
        }
        val statement = Statement.create(
            """
            SELECT $COLUMNS FROM artifacts_artifacts a WHERE a.org_id = :org_id AND (CAST(:admin AS BOOLEAN) OR
            a.owner_id = :member OR a.access <> 'personal' OR EXISTS (SELECT 1 FROM artifacts_members m WHERE
            m.artifact_id = a.id AND m.member_id = :member))$cursor ORDER BY a.updated_at DESC, a.id DESC LIMIT
            :limit
            """,
        ).bind("org_id", caller.orgId)
            .bind("admin", caller.admin)
            .bind("member", caller.memberId)
            .bind("limit", limit)
        if (before != null) statement.bind("before", before)
        return driver.fetchAll(statement).getOrThrow().map { it.toArtifact() }
    }

    override suspend fun versions(artifactId: Uuid): List<ArtifactVersion> = driver.fetchAll(
        Statement.create(
            """
            SELECT $VERSION_COLUMNS FROM artifacts_versions WHERE artifact_id = :id ORDER BY number DESC
            """,
        ).bind("id", artifactId),
    ).getOrThrow().map { it.toVersion() }

    override suspend fun version(artifactId: Uuid, number: Int): ArtifactVersion? = driver.fetchAll(
        Statement.create(
            """
            SELECT $VERSION_COLUMNS FROM artifacts_versions WHERE artifact_id = :id AND number = :number
            """,
        ).bind("id", artifactId).bind("number", number),
    ).getOrThrow().firstOrNull()?.toVersion()

    override suspend fun blob(artifactId: Uuid, sha256: String): ByteArray? = driver.fetchAll(
        Statement.create("""SELECT bytes FROM artifacts_blobs WHERE artifact_id = :id AND sha256 = :sha""")
            .bind("id", artifactId).bind("sha", sha256),
    ).getOrThrow().firstOrNull()?.get("bytes")?.asByteArray()

    override suspend fun blobDigests(artifactId: Uuid): Set<String> = driver.fetchAll(
        Statement.create("""SELECT sha256 FROM artifacts_blobs WHERE artifact_id = :id""").bind("id", artifactId),
    ).getOrThrow().map { it.get("sha256").asString() }.toSet()

    override suspend fun members(artifactId: Uuid): List<String> = driver.fetchAll(
        Statement.create(
            """SELECT member_id FROM artifacts_members WHERE artifact_id = :id ORDER BY member_id""",
        ).bind("id", artifactId),
    ).getOrThrow().map { it.get("member_id").asString() }

    override suspend fun setMember(orgId: Uuid, artifactId: Uuid, memberId: String, share: Boolean) {
        val sql = if (share) {
            """
            INSERT INTO artifacts_members (artifact_id, member_id) SELECT id, :member FROM artifacts_artifacts WHERE
            id = :id AND org_id = :org_id ON CONFLICT DO NOTHING
            """
        } else {
            """
            DELETE FROM artifacts_members WHERE member_id = :member AND artifact_id IN (SELECT id FROM
            artifacts_artifacts WHERE id = :id AND org_id = :org_id)
            """
        }
        driver.execute(
            Statement.create(sql).bind("member", memberId).bind("id", artifactId).bind("org_id", orgId),
        ).getOrThrow()
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = driver.execute(
        Statement.create("""DELETE FROM artifacts_artifacts WHERE id = :id AND org_id = :org_id""")
            .bind("id", id).bind("org_id", orgId),
    ).getOrThrow() > 0

    private suspend fun QueryExecutor.insertContent(version: ArtifactVersion, blobs: Map<String, ByteArray>) {
        blobs.forEach { (sha, bytes) ->
            execute(
                Statement.create(
                    """
                    INSERT INTO artifacts_blobs (artifact_id, sha256, bytes) VALUES (:id, :sha, CAST(:bytes AS
                    BYTEA)) ON CONFLICT DO NOTHING
                    """,
                ).bind("id", version.artifactId).bind("sha", sha).bind("bytes", bytes),
            ).getOrThrow()
        }
        val files = version.files.map { FileRow(it.path, it.sha256, it.size, it.contentType) }
        execute(
            Statement.create(
                """
                INSERT INTO artifacts_versions ($VERSION_COLUMNS) VALUES (:id, :number, :entry, :files,
                :created_at, :created_by)
                """,
            ).bind("id", version.artifactId)
                .bind("number", version.number)
                .bind("entry", version.entry)
                .bind("files", JSON.encodeToString(FILES, files))
                .bind("created_at", version.createdAt)
                .bind("created_by", version.createdBy),
        ).getOrThrow()
    }

    private fun ResultSet.Row.toArtifact() = Artifact(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        ownerId = get("owner_id").asString(),
        title = get("title").asString(),
        access = storedAccess(get("access").asString()),
        latestVersion = get("latest_version").asInt(),
        linkSlug = get("link_slug").asStringOrNull(),
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
    )

    /** Reads a stored access level; anything unexpected is read as the narrowest, `personal`. */
    private fun storedAccess(wire: String): ArtifactAccess = when (wire) {
        "team" -> ArtifactAccess.TEAM
        "link" -> ArtifactAccess.LINK
        else -> ArtifactAccess.PERSONAL
    }

    private fun ResultSet.Row.toVersion() = ArtifactVersion(
        artifactId = get("artifact_id").asUuid(),
        number = get("number").asInt(),
        entry = get("entry").asString(),
        files = JSON.decodeFromString(FILES, get("files").asString())
            .map { ArtifactFile(it.path, it.sha256, it.size, it.type) },
        createdAt = get("created_at").asInstant(),
        createdBy = get("created_by").asString(),
    )

    /** A file as stored in `artifacts_versions.files`. */
    @Serializable
    private data class FileRow(val path: String, val sha256: String, val size: Long, val type: String)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val FILES = ListSerializer(FileRow.serializer())

        const val COLUMNS = """
            id, org_id, owner_id, title, access, latest_version, link_slug, created_at, updated_at
        """
        const val VERSION_COLUMNS = """artifact_id, number, entry, files, created_at, created_by"""
    }
}
