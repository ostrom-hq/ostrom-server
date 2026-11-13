// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.fakes

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.uploads.ArtifactUpload
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadsRepository
import run.ostrom.server.artifacts.domain.uploads.UploadBlobState
import run.ostrom.server.artifacts.domain.uploads.UploadLimits
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** [ArtifactUploadsRepository] in memory, its drafts and stored files kept in [artifacts] as the database does. */
class InMemoryUploads(private val artifacts: InMemoryArtifacts) : ArtifactUploadsRepository {
    val uploads = mutableMapOf<Uuid, ArtifactUpload>()
    val chunks = mutableMapOf<Pair<Uuid, String>, MutableSet<Int>>()

    override suspend fun open(upload: ArtifactUpload, draft: Artifact?, now: Instant): Boolean {
        val open = uploads.values.count { it.memberId == upload.memberId && it.expiresAt > now }
        val drafts = artifacts.artifacts.values.count { it.ownerId == upload.memberId && it.draft }
        val busy = open >= UploadLimits.OPEN_UPLOADS || (draft != null && drafts >= UploadLimits.DRAFTS)
        if (!busy) {
            draft?.let { artifacts.artifacts[it.id] = it }
            uploads[upload.id] = upload
        }
        return !busy
    }

    override suspend fun get(orgId: Uuid, id: Uuid): ArtifactUpload? = uploads[id]?.takeIf { it.orgId == orgId }

    override suspend fun receive(orgId: Uuid, uploadId: Uuid, sha256: String, index: Int): Int =
        chunks.getOrPut(uploadId to sha256) { mutableSetOf() }.apply { add(index) }.size

    override suspend fun claim(orgId: Uuid, uploadId: Uuid, sha256: String): Boolean =
        move(uploadId, sha256, UploadBlobState.WAITING, UploadBlobState.ASSEMBLING)

    override suspend fun stored(orgId: Uuid, upload: ArtifactUpload, sha256: String, size: Long) {
        move(upload.id, sha256, UploadBlobState.ASSEMBLING, UploadBlobState.STORED)
        artifacts.blobs[upload.artifactId to sha256] = size
    }

    override suspend fun retry(orgId: Uuid, uploadId: Uuid, sha256: String) {
        chunks.remove(uploadId to sha256)
        move(uploadId, sha256, UploadBlobState.ASSEMBLING, UploadBlobState.WAITING)
    }

    override suspend fun end(orgId: Uuid, upload: ArtifactUpload): Boolean {
        uploads.remove(upload.id)
        val draft = artifacts.artifacts[upload.artifactId]?.takeIf { it.draft }
        val dropped = draft != null && uploads.values.none { it.artifactId == draft.id }
        if (dropped) artifacts.delete(orgId, upload.artifactId)
        return dropped
    }

    /** Puts a file of an upload in [to] when it is in [from]. */
    fun move(uploadId: Uuid, sha256: String, from: UploadBlobState, to: UploadBlobState): Boolean {
        val upload = uploads[uploadId] ?: return false
        val moved = upload.files.any { it.sha256 == sha256 && it.state == from }
        if (moved) {
            uploads[uploadId] = upload.copy(
                files = upload.files.map { if (it.sha256 == sha256) it.copy(state = to) else it },
            )
        }
        return moved
    }

    /** Where a file of an upload stands. */
    fun state(uploadId: Uuid, sha256: String): UploadBlobState? =
        uploads[uploadId]?.files?.firstOrNull { it.sha256 == sha256 }?.state
}
