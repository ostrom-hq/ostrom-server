// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.uploads.ArtifactUpload
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadsRepository
import run.ostrom.server.artifacts.domain.uploads.UploadBlob
import run.ostrom.server.artifacts.domain.uploads.UploadBlobState
import run.ostrom.server.artifacts.domain.uploads.UploadLimits
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.kernel.database.written
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactUploadsRepository] on PostgreSQL (version 6 of [ArtifactsMigrations]), every statement confined to the
 * upload's organization (`inOrg`). The limits of a member are counted under an advisory lock on the member, the
 * chunks of a file under a row lock on the file, so that replicas agree.
 *
 * @param database The serving role's pool.
 */
internal class ArtifactUploadsRepositoryImpl(private val database: JdbcDatabase) : ArtifactUploadsRepository {
    override suspend fun open(upload: ArtifactUpload, draft: Artifact?, now: Instant): Boolean =
        database.inOrg(upload.orgId) {
            // Serializes one member's openings within this transaction (released at its end).
            lockKeyUntilCommit("artifacts-uploads:${upload.orgId}:${upload.memberId}")
            val busy = openUploads(upload.orgId, upload.memberId, now).single() >= UploadLimits.OPEN_UPLOADS ||
                (draft != null && drafts(upload.orgId, upload.memberId).single() >= UploadLimits.DRAFTS)
            if (!busy) {
                if (draft != null) insertDraft(draft)
                insert(upload)
            }
            !busy
        }

    override suspend fun get(orgId: Uuid, id: Uuid): ArtifactUpload? = database.inOrg(orgId) {
        val u = ArtifactsUploads
        val row = u.select(u.columns).where { (u.id eq id) and (u.orgId eq orgId) }.firstOrNull()
        row?.let {
            val b = ArtifactsUploadBlobs
            val files = b.select(b.sha256, b.size, b.state)
                .where { (b.uploadId eq id) and (b.orgId eq orgId) }
                .orderBy(b.position)
                .map { file -> UploadBlob(file[b.sha256], file[b.size], UploadBlobState.parse(file[b.state])) }
            it.toUpload(files)
        }
    }

    override suspend fun receive(orgId: Uuid, uploadId: Uuid, sha256: String, index: Int): Int =
        database.inOrg(orgId) {
            // The file's row is locked first: the chunks are counted once the others' are committed, so the last
            // chunk to arrive always sees them all, whichever replica it reaches.
            fileLocked(orgId, uploadId, sha256).toList()
            // A bare `ON CONFLICT DO NOTHING`, as before: the chunk's key is its only constraint.
            ArtifactsUploadChunks.insertIgnore {
                it[this.uploadId] = uploadId
                it[this.orgId] = orgId
                it[this.sha256] = sha256
                it[chunkIndex] = index
            }
            chunksReceived(orgId, uploadId, sha256).single().toInt()
        }

    override suspend fun claim(orgId: Uuid, uploadId: Uuid, sha256: String): Boolean = database.inOrg(orgId) {
        written { fileClaimed(orgId, uploadId, sha256) } > 0
    }

    override suspend fun stored(orgId: Uuid, upload: ArtifactUpload, sha256: String, size: Long) {
        database.inOrg(orgId) {
            val b = ArtifactsUploadBlobs
            b.update({ (b.uploadId eq upload.id) and (b.orgId eq orgId) and (b.sha256 eq sha256) }) {
                it[state] = UploadBlobState.STORED.wire
            }
            // The content is kept, and no longer marked for collection.
            val blobs = ArtifactsBlobs
            blobs.upsert(blobs.artifactId, blobs.sha256, onUpdate = { it[blobs.markedAt] = null }) {
                it[artifactId] = upload.artifactId
                it[this.orgId] = orgId
                it[this.sha256] = sha256
                it[this.size] = size
            }
        }
    }

    override suspend fun retry(orgId: Uuid, uploadId: Uuid, sha256: String) {
        database.inOrg(orgId) {
            val c = ArtifactsUploadChunks
            c.deleteWhere { (c.uploadId eq uploadId) and (c.orgId eq orgId) and (c.sha256 eq sha256) }
            val b = ArtifactsUploadBlobs
            b.update({
                (b.uploadId eq uploadId) and (b.orgId eq orgId) and (b.sha256 eq sha256) and
                    (b.state eq UploadBlobState.ASSEMBLING.wire)
            }) { it[state] = UploadBlobState.WAITING.wire }
        }
    }

    override suspend fun end(orgId: Uuid, upload: ArtifactUpload): Boolean = database.inOrg(orgId) {
        val u = ArtifactsUploads
        u.deleteWhere { (u.id eq upload.id) and (u.orgId eq orgId) }
        val a = ArtifactsArtifacts
        val others = u.select(intLiteral(1)).where { u.artifactId eq a.id }
        a.deleteWhere {
            (a.id eq upload.artifactId) and (a.orgId eq orgId) and (a.latestVersion eq 0) and notExists(others)
        } > 0
    }

    private fun JdbcTransaction.insertDraft(draft: Artifact) {
        val a = ArtifactsArtifacts
        a.insert {
            it[id] = draft.id
            it[orgId] = draft.orgId
            it[ownerId] = draft.ownerId
            it[title] = draft.title
            it[access] = draft.access.wire
            it[latestVersion] = 0
            it[linkSlug] = null
            it[createdAt] = draft.createdAt
            it[updatedAt] = draft.createdAt
            it[originLaunchId] = draft.origin?.launchId
            it[originSessionId] = draft.origin?.cloudSessionId
        }
        ArtifactsRoutes.insert {
            it[artifactId] = draft.id
            it[orgId] = draft.orgId
        }
    }

    private fun JdbcTransaction.insert(upload: ArtifactUpload) {
        ArtifactsUploads.insert {
            it[id] = upload.id
            it[orgId] = upload.orgId
            it[artifactId] = upload.artifactId
            it[memberId] = upload.memberId
            it[credentialId] = upload.credentialId
            it[createdAt] = upload.createdAt
            it[expiresAt] = upload.expiresAt
        }
        upload.files.forEachIndexed { position, file ->
            ArtifactsUploadBlobs.insert {
                it[uploadId] = upload.id
                it[orgId] = upload.orgId
                it[sha256] = file.sha256
                it[size] = file.size
                it[state] = file.state.wire
                it[this.position] = position
            }
        }
    }

    private fun ResultRow.toUpload(files: List<UploadBlob>) = ArtifactUpload(
        id = this[ArtifactsUploads.id],
        orgId = this[ArtifactsUploads.orgId],
        artifactId = this[ArtifactsUploads.artifactId],
        memberId = this[ArtifactsUploads.memberId],
        credentialId = this[ArtifactsUploads.credentialId],
        files = files,
        createdAt = this[ArtifactsUploads.createdAt],
        expiresAt = this[ArtifactsUploads.expiresAt],
    )
}

/** The uploads a member holds open: `artifacts_uploads_member`. */
internal fun openUploads(orgId: Uuid, memberId: String, now: Instant): Read<Long> {
    val u = ArtifactsUploads
    val open = u.id.count()
    val query = u.select(open)
        .where { (u.orgId eq orgId) and (u.memberId eq memberId) and (u.expiresAt greater now) }
    return Read(query) { it[open] }
}

/** The drafts a member holds: the partial index `artifacts_artifacts_drafts`. */
internal fun drafts(orgId: Uuid, memberId: String): Read<Long> {
    val a = ArtifactsArtifacts
    val held = a.id.count()
    val query = a.select(held).where { (a.orgId eq orgId) and (a.ownerId eq memberId) and (a.latestVersion eq 0) }
    return Read(query) { it[held] }
}

/** The row of an announced file, locked until the transaction ends. */
internal fun fileLocked(orgId: Uuid, uploadId: Uuid, sha256: String): Query {
    val b = ArtifactsUploadBlobs
    return b.select(intLiteral(1))
        .where { (b.uploadId eq uploadId) and (b.orgId eq orgId) and (b.sha256 eq sha256) }
        .forUpdate()
}

/** The chunks of a file received: the primary key of `artifacts_upload_chunks`. */
internal fun chunksReceived(orgId: Uuid, uploadId: Uuid, sha256: String): Read<Long> {
    val c = ArtifactsUploadChunks
    val received = c.chunkIndex.count()
    val query = c.select(received).where { (c.uploadId eq uploadId) and (c.orgId eq orgId) and (c.sha256 eq sha256) }
    return Read(query) { it[received] }
}

/** A waiting file taken to be assembled: only one caller gets it. */
internal fun StatementBuilder.fileClaimed(orgId: Uuid, uploadId: Uuid, sha256: String): UpdateStatement {
    val b = ArtifactsUploadBlobs
    return b.update({
        (b.uploadId eq uploadId) and (b.orgId eq orgId) and (b.sha256 eq sha256) and
            (b.state eq UploadBlobState.WAITING.wire)
    }) { it[state] = UploadBlobState.ASSEMBLING.wire }
}
