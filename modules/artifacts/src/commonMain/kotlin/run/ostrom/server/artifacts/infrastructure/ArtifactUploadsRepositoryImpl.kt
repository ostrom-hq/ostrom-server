// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.uploads.ArtifactUpload
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadsRepository
import run.ostrom.server.artifacts.domain.uploads.UploadBlob
import run.ostrom.server.artifacts.domain.uploads.UploadBlobState
import run.ostrom.server.artifacts.domain.uploads.UploadLimits
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactUploadsRepository] on PostgreSQL (version 6 of [ArtifactsMigrations]), every statement confined to the
 * upload's organization ([inTenant]). The limits of a member are counted under an advisory lock on the member, the
 * chunks of a file under a row lock on the file, so that replicas agree.
 *
 * @param driver The database.
 */
internal class ArtifactUploadsRepositoryImpl(private val driver: Driver) : ArtifactUploadsRepository {
    override suspend fun open(upload: ArtifactUpload, draft: Artifact?, now: Instant): Boolean =
        driver.inTenant(upload.orgId) {
            execute(
                Statement.create(MEMBER_LOCK).bind("org_id", upload.orgId).bind("member", upload.memberId),
            ).getOrThrow()
            val open = Statement.create(UploadQueries.OPEN_UPLOADS).bind("now", now)
            val busy = count(open, upload) >= UploadLimits.OPEN_UPLOADS ||
                (draft != null && count(Statement.create(UploadQueries.DRAFTS), upload) >= UploadLimits.DRAFTS)
            if (!busy) {
                if (draft != null) insertDraft(draft)
                insert(upload)
            }
            !busy
        }

    override suspend fun get(orgId: Uuid, id: Uuid): ArtifactUpload? = driver.inTenant(orgId) {
        val row = fetchAll(
            Statement.create("SELECT $COLUMNS FROM artifacts_uploads WHERE id = :id AND org_id = :org_id")
                .bind("id", id).bind("org_id", orgId),
        ).getOrThrow().firstOrNull()
        row?.let {
            val files = fetchAll(
                Statement.create(
                    "SELECT sha256, size, state FROM artifacts_upload_blobs WHERE upload_id = :id ORDER BY position",
                ).bind("id", id),
            ).getOrThrow().map { file ->
                UploadBlob(
                    file.get("sha256").asString(),
                    file.get("size").asLong(),
                    UploadBlobState.parse(file.get("state").asString()),
                )
            }
            ArtifactUpload(
                id = it.get("id").asUuid(),
                orgId = it.get("org_id").asUuid(),
                artifactId = it.get("artifact_id").asUuid(),
                memberId = it.get("member_id").asString(),
                credentialId = it.get("credential_id").asStringOrNull(),
                files = files,
                createdAt = it.get("created_at").asInstant(),
                expiresAt = it.get("expires_at").asInstant(),
            )
        }
    }

    override suspend fun receive(orgId: Uuid, uploadId: Uuid, sha256: String, index: Int): Int =
        driver.inTenant(orgId) {
            // The file's row is locked first: the chunks are counted once the others' are committed, so the last
            // chunk to arrive always sees them all, whichever replica it reaches.
            fetchAll(
                Statement.create(
                    "SELECT 1 FROM artifacts_upload_blobs WHERE upload_id = :id AND sha256 = :sha FOR UPDATE",
                ).bind("id", uploadId).bind("sha", sha256),
            ).getOrThrow()
            execute(
                Statement.create(
                    """
                    INSERT INTO artifacts_upload_chunks (upload_id, org_id, sha256, chunk_index)
                    VALUES (:id, :org_id, :sha, :index) ON CONFLICT DO NOTHING
                    """,
                ).bind("id", uploadId).bind("org_id", orgId).bind("sha", sha256).bind("index", index),
            ).getOrThrow()
            fetchAll(
                Statement.create(UploadQueries.RECEIVED).bind("id", uploadId).bind("sha", sha256),
            ).getOrThrow().single().get("n").asLong().toInt()
        }

    override suspend fun claim(orgId: Uuid, uploadId: Uuid, sha256: String): Boolean = driver.inTenant(orgId) {
        execute(
            Statement.create(
                """
                UPDATE artifacts_upload_blobs SET state = 'assembling' WHERE upload_id = :id AND sha256 = :sha AND
                state = 'waiting'
                """,
            ).bind("id", uploadId).bind("sha", sha256),
        ).getOrThrow() > 0
    }

    override suspend fun stored(orgId: Uuid, upload: ArtifactUpload, sha256: String, size: Long) {
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    "UPDATE artifacts_upload_blobs SET state = 'stored' WHERE upload_id = :id AND sha256 = :sha",
                ).bind("id", upload.id).bind("sha", sha256),
            ).getOrThrow()
            execute(
                Statement.create(
                    """
                    INSERT INTO artifacts_blobs (artifact_id, org_id, sha256, size) VALUES (:artifact, :org_id, :sha,
                    :size) ON CONFLICT DO NOTHING
                    """,
                ).bind("artifact", upload.artifactId).bind("org_id", orgId).bind("sha", sha256).bind("size", size),
            ).getOrThrow()
        }
    }

    override suspend fun retry(orgId: Uuid, uploadId: Uuid, sha256: String) {
        driver.inTenant(orgId) {
            execute(
                Statement.create("DELETE FROM artifacts_upload_chunks WHERE upload_id = :id AND sha256 = :sha")
                    .bind("id", uploadId).bind("sha", sha256),
            ).getOrThrow()
            execute(
                Statement.create(
                    """
                    UPDATE artifacts_upload_blobs SET state = 'waiting' WHERE upload_id = :id AND sha256 = :sha AND
                    state = 'assembling'
                    """,
                ).bind("id", uploadId).bind("sha", sha256),
            ).getOrThrow()
        }
    }

    override suspend fun end(orgId: Uuid, upload: ArtifactUpload): Boolean = driver.inTenant(orgId) {
        execute(
            Statement.create("DELETE FROM artifacts_uploads WHERE id = :id AND org_id = :org_id")
                .bind("id", upload.id).bind("org_id", orgId),
        ).getOrThrow()
        execute(
            Statement.create(
                """
                DELETE FROM artifacts_artifacts a WHERE a.id = :artifact AND a.org_id = :org_id AND
                a.latest_version = 0 AND NOT EXISTS (SELECT 1 FROM artifacts_uploads u WHERE u.artifact_id = a.id)
                """,
            ).bind("artifact", upload.artifactId).bind("org_id", orgId),
        ).getOrThrow() > 0
    }

    private suspend fun QueryExecutor.count(statement: Statement, upload: ArtifactUpload): Long = fetchAll(
        statement.bind("org_id", upload.orgId).bind("member", upload.memberId),
    ).getOrThrow().single().get("n").asLong()

    private suspend fun QueryExecutor.insertDraft(draft: Artifact) {
        execute(
            Statement.create(
                """
                INSERT INTO artifacts_artifacts (id, org_id, owner_id, title, access, latest_version, link_slug,
                created_at, updated_at) VALUES (:id, :org_id, :owner_id, :title, :access, 0, NULL, :now, :now)
                """,
            ).bind("id", draft.id)
                .bind("org_id", draft.orgId)
                .bind("owner_id", draft.ownerId)
                .bind("title", draft.title)
                .bind("access", draft.access.wire)
                .bind("now", draft.createdAt),
        ).getOrThrow()
        execute(
            Statement.create("INSERT INTO artifacts_routes (artifact_id, org_id) VALUES (:id, :org_id)")
                .bind("id", draft.id).bind("org_id", draft.orgId),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.insert(upload: ArtifactUpload) {
        execute(
            Statement.create(
                """
                INSERT INTO artifacts_uploads ($COLUMNS) VALUES (:id, :org_id, :artifact_id, :member_id,
                :credential_id, :created_at, :expires_at)
                """,
            ).bind("id", upload.id)
                .bind("org_id", upload.orgId)
                .bind("artifact_id", upload.artifactId)
                .bind("member_id", upload.memberId)
                .bind("credential_id", upload.credentialId)
                .bind("created_at", upload.createdAt)
                .bind("expires_at", upload.expiresAt),
        ).getOrThrow()
        upload.files.forEachIndexed { position, file ->
            execute(
                Statement.create(
                    """
                    INSERT INTO artifacts_upload_blobs (upload_id, org_id, sha256, size, state, position)
                    VALUES (:id, :org_id, :sha, :size, :state, :position)
                    """,
                ).bind("id", upload.id)
                    .bind("org_id", upload.orgId)
                    .bind("sha", file.sha256)
                    .bind("size", file.size)
                    .bind("state", file.state.wire)
                    .bind("position", position),
            ).getOrThrow()
        }
    }

    private companion object {
        const val COLUMNS = "id, org_id, artifact_id, member_id, credential_id, created_at, expires_at"

        /** Serializes one member's openings within a transaction (released at its end). */
        const val MEMBER_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended('artifacts-uploads:' || " +
            "CAST(:org_id AS TEXT) || ':' || :member, 0))"

    }
}

/** The request-path reads of uploads, under `EXPLAIN ANALYZE` in their plans test. */
internal object UploadQueries {
    /** The uploads a member holds open: `artifacts_uploads_member`. */
    const val OPEN_UPLOADS = "SELECT COUNT(*) AS n FROM artifacts_uploads WHERE org_id = :org_id AND " +
        "member_id = :member AND expires_at > :now"

    /** The drafts a member holds: the partial index `artifacts_artifacts_drafts`. */
    const val DRAFTS = "SELECT COUNT(*) AS n FROM artifacts_artifacts WHERE org_id = :org_id AND " +
        "owner_id = :member AND latest_version = 0"

    /** The chunks of a file received: the primary key of `artifacts_upload_chunks`. */
    const val RECEIVED = "SELECT COUNT(*) AS n FROM artifacts_upload_chunks WHERE upload_id = :id AND sha256 = :sha"
}
