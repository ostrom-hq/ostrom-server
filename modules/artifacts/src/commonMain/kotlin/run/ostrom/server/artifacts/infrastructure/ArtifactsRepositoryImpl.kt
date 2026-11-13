// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.LegacyBlob
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
 * `artifacts_versions.files`; each distinct content is one `artifacts_blobs` row per artifact (its size; the bytes are
 * in the organization's bucket, [run.ostrom.server.artifacts.domain.storage.ArtifactContents]), so republishing an
 * unchanged file stores nothing. A row that still holds `bytes` predates the bucket and waits to be moved there.
 *
 * Every read and write runs confined to the artifact's organization ([inTenant], S2-01). Only `artifacts_routes` is
 * read across organizations, only through its `SECURITY DEFINER` functions: the content origin finds an artifact's
 * organization there by its id or its link slug, then reads the artifact in its organization.
 *
 * @param driver The database.
 */
@Suppress("TooManyFunctions") // One implementation of the module's one port.
internal class ArtifactsRepositoryImpl(private val driver: Driver) : ArtifactsRepository {
    override suspend fun create(artifact: Artifact, version: ArtifactVersion) {
        driver.inTenant(artifact.orgId) {
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
            execute(
                Statement.create(
                    """INSERT INTO artifacts_routes (artifact_id, org_id, link_slug) VALUES (:id, :org_id, :slug)""",
                ).bind("id", artifact.id).bind("org_id", artifact.orgId).bind("slug", artifact.linkSlug),
            ).getOrThrow()
            insertContent(artifact.orgId, version)
        }
    }

    override suspend fun addVersion(orgId: Uuid, version: ArtifactVersion, now: Instant): Artifact? =
        driver.inTenant(orgId) {
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
            if (updated != null) insertContent(orgId, version)
            updated
        }

    override suspend fun updateSettings(artifact: Artifact) {
        driver.inTenant(artifact.orgId) {
            execute(
                Statement.create(
                    """
                    UPDATE artifacts_artifacts SET title = :title, access = :access, link_slug = :link_slug,
                    updated_at = :updated_at WHERE id = :id AND org_id = :org_id
                    """,
                ).bind("title", artifact.title)
                    .bind("access", artifact.access.wire)
                    .bind("link_slug", artifact.linkSlug)
                    .bind("updated_at", artifact.updatedAt)
                    .bind("id", artifact.id)
                    .bind("org_id", artifact.orgId),
            ).getOrThrow()
            execute(
                Statement.create(
                    """UPDATE artifacts_routes SET link_slug = :slug WHERE artifact_id = :id AND org_id = :org_id""",
                ).bind("slug", artifact.linkSlug).bind("id", artifact.id).bind("org_id", artifact.orgId),
            ).getOrThrow()
        }
    }

    override suspend fun get(orgId: Uuid, id: Uuid): Artifact? = driver.fetchInOrg(
        orgId,
        Statement.create("""SELECT $COLUMNS FROM artifacts_artifacts WHERE id = :id AND org_id = :org_id""")
            .bind("id", id).bind("org_id", orgId),
    ).getOrThrow().firstOrNull()?.toArtifact()

    override suspend fun getById(id: Uuid): Artifact? = route("artifacts_route_by_id", "UUID", id)

    override suspend fun getByLinkSlug(slug: String): Artifact? = route("artifacts_route_by_slug", "TEXT", slug)

    override suspend fun listReadable(caller: ArtifactCaller, before: Uuid?, limit: Int): List<Artifact> {
        val cursor = if (before == null) {
            ""
        } else {
            """ AND (a.updated_at, a.id) <
                (SELECT updated_at, id FROM artifacts_artifacts WHERE id = :before AND org_id = :org_id)"""
        }
        val statement = Statement.create(
            """
            SELECT $COLUMNS FROM artifacts_artifacts a WHERE a.org_id = :org_id AND (a.owner_id = :member OR
            a.access <> 'personal' OR EXISTS (SELECT 1 FROM artifacts_members m WHERE m.artifact_id = a.id AND
            m.member_id = :member))$cursor ORDER BY a.updated_at DESC, a.id DESC LIMIT :limit
            """,
        ).bind("org_id", caller.orgId)
            .bind("member", caller.memberId)
            .bind("limit", limit)
        if (before != null) statement.bind("before", before)
        return driver.fetchInOrg(caller.orgId, statement).getOrThrow().map { it.toArtifact() }
    }

    override suspend fun versions(orgId: Uuid, artifactId: Uuid): List<ArtifactVersion> = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            SELECT $VERSION_COLUMNS FROM artifacts_versions WHERE artifact_id = :id ORDER BY number DESC
            """,
        ).bind("id", artifactId),
    ).getOrThrow().map { it.toVersion() }

    override suspend fun version(orgId: Uuid, artifactId: Uuid, number: Int): ArtifactVersion? = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            SELECT $VERSION_COLUMNS FROM artifacts_versions WHERE artifact_id = :id AND number = :number
            """,
        ).bind("id", artifactId).bind("number", number),
    ).getOrThrow().firstOrNull()?.toVersion()

    override suspend fun legacyBlobs(orgId: Uuid, limit: Int): List<LegacyBlob> = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            SELECT artifact_id, sha256, bytes FROM artifacts_blobs WHERE bytes IS NOT NULL
            ORDER BY artifact_id, sha256 LIMIT :limit
            """,
        ).bind("limit", limit),
    ).getOrThrow().map {
        LegacyBlob(it.get("artifact_id").asUuid(), it.get("sha256").asString(), it.get("bytes").asByteArray())
    }

    override suspend fun releaseLegacyBlob(orgId: Uuid, artifactId: Uuid, sha256: String) {
        driver.inTenant(orgId) {
            execute(
                Statement.create("UPDATE artifacts_blobs SET bytes = NULL WHERE artifact_id = :id AND sha256 = :sha")
                    .bind("id", artifactId).bind("sha", sha256),
            ).getOrThrow()
        }
    }

    override suspend fun blobDigests(orgId: Uuid, artifactId: Uuid): Set<String> = driver.fetchInOrg(
        orgId,
        Statement.create("""SELECT sha256 FROM artifacts_blobs WHERE artifact_id = :id""").bind("id", artifactId),
    ).getOrThrow().map { it.get("sha256").asString() }.toSet()

    override suspend fun members(orgId: Uuid, artifactId: Uuid): List<String> = driver.fetchInOrg(
        orgId,
        Statement.create(
            """SELECT member_id FROM artifacts_members WHERE artifact_id = :id ORDER BY member_id""",
        ).bind("id", artifactId),
    ).getOrThrow().map { it.get("member_id").asString() }

    override suspend fun setMember(orgId: Uuid, artifactId: Uuid, memberId: String, share: Boolean) {
        val sql = if (share) {
            """
            INSERT INTO artifacts_members (artifact_id, org_id, member_id) SELECT id, org_id, :member FROM
            artifacts_artifacts WHERE id = :id AND org_id = :org_id ON CONFLICT DO NOTHING
            """
        } else {
            """
            DELETE FROM artifacts_members WHERE member_id = :member AND artifact_id IN (SELECT id FROM
            artifacts_artifacts WHERE id = :id AND org_id = :org_id)
            """
        }
        driver.inTenant(orgId) {
            execute(
                Statement.create(sql).bind("member", memberId).bind("id", artifactId).bind("org_id", orgId),
            ).getOrThrow()
        }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = driver.inTenant(orgId) {
        execute(
            Statement.create("""DELETE FROM artifacts_artifacts WHERE id = :id AND org_id = :org_id""")
                .bind("id", id).bind("org_id", orgId),
        ).getOrThrow() > 0
    }

    /**
     * The artifact a route matches, found across organizations by the `SECURITY DEFINER` [function] of
     * [ArtifactsMigrations] (version 2) on `:key`, then read in its organization.
     */
    private suspend fun route(function: String, type: String, key: Any): Artifact? {
        val row = driver.fetchAll(
            Statement.create("""SELECT artifact_id, org_id FROM $function(CAST(:key AS $type))""").bind("key", key),
        ).getOrThrow().firstOrNull() ?: return null
        return get(row.get("org_id").asUuid(), row.get("artifact_id").asUuid())
    }

    // One row per distinct content of the artifact, its size only: the bytes are in the bucket.
    private suspend fun QueryExecutor.insertContent(orgId: Uuid, version: ArtifactVersion) {
        version.files.distinctBy { it.sha256 }.forEach { file ->
            execute(
                Statement.create(
                    """
                    INSERT INTO artifacts_blobs (artifact_id, org_id, sha256, size) VALUES (:id, :org_id, :sha, :size)
                    ON CONFLICT DO NOTHING
                    """,
                ).bind("id", version.artifactId).bind("org_id", orgId).bind("sha", file.sha256)
                    .bind("size", file.size),
            ).getOrThrow()
        }
        val files = version.files.map { FileRow(it.path, it.sha256, it.size, it.contentType) }
        execute(
            Statement.create(
                """
                INSERT INTO artifacts_versions (org_id, $VERSION_COLUMNS) VALUES (:org_id, :id, :number, :entry,
                :files, :created_at, :created_by)
                """,
            ).bind("org_id", orgId)
                .bind("id", version.artifactId)
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
        private val JSON = Json { ignoreUnknownKeys = true }
        private val FILES = ListSerializer(FileRow.serializer())

        const val COLUMNS = """
            id, org_id, owner_id, title, access, latest_version, link_slug, created_at, updated_at
        """
        const val VERSION_COLUMNS = """artifact_id, number, entry, files, created_at, created_by"""
    }
}
