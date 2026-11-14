// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.uploads

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactAccessRules
import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactEvents
import run.ostrom.server.artifacts.domain.ArtifactRules
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.ContentDigest
import run.ostrom.server.artifacts.domain.PublicationGate
import run.ostrom.server.artifacts.domain.ensureValid
import run.ostrom.server.artifacts.domain.storage.ArtifactContents
import run.ostrom.server.artifacts.domain.storage.UploadChunks
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Uploads the files of an artifact's next version in chunks (TeamCode's two-step publication, `docs/artifacts.md`
 * "Publication API"): the publisher announces the distinct files, sends only those the artifact's folder lacks, in
 * chunks of at most 1 MiB in any order, then commits the version. While artifacts are inactive, every call is refused
 * with TeamCode's reason ([run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException]).
 */
interface UploadArtifactUseCase {
    /**
     * Opens an upload: without [artifactId], a draft artifact is created for the caller.
     *
     * @param artifactId An artifact of the caller's, or null for a new one.
     * @param files The distinct files of the version.
     *
     * @return The upload; [ArtifactUpload.missing] are the files to send, before [ArtifactUpload.expiresAt].
     *
     * @throws run.ostrom.server.artifacts.domain.InvalidArtifactRequestException When a file is malformed or too large.
     * @throws run.ostrom.server.artifacts.domain.ArtifactNotFoundException When the artifact is not readable.
     * @throws run.ostrom.server.artifacts.domain.ArtifactNotWritableException When it is not the caller's.
     * @throws ArtifactUploadsBusyException When the caller holds too many uploads or drafts.
     * @throws run.ostrom.server.artifacts.domain.ArtifactBusyException While contents of the artifact are collected.
     */
    suspend fun open(caller: ArtifactCaller, artifactId: Uuid?, files: List<AnnouncedFile>): ArtifactUpload

    /**
     * Receives one chunk of a file; the last one to arrive assembles the file, checks it and stores it in the
     * artifact's folder.
     *
     * @param index The chunk, from 0.
     *
     * @return Where the file stands.
     *
     * @throws ArtifactUploadNotFoundException When the upload is closed or another credential's, or the file is not
     *   one it waits for.
     * @throws run.ostrom.server.artifacts.domain.InvalidArtifactRequestException When the chunk is not the size its
     *   index holds.
     * @throws ArtifactBlobMismatchException When the file is not the one announced (send it again).
     * @throws ArtifactSecretDetectedException When it holds a credential (the upload ends).
     * @throws ArtifactFileTooLargeException When a binary file is larger than its bound (the upload ends).
     */
    suspend fun chunk(
        caller: ArtifactCaller,
        uploadId: Uuid,
        sha256: String,
        index: Int,
        bytes: ByteArray,
    ): ChunkReceipt
}

/**
 * Where uploads stand and where their chunks wait.
 *
 * @property uploads The uploads, without bytes.
 * @property chunks Their chunks, in the bucket.
 * @property gate The quotas a new artifact's draft is checked on.
 */
class UploadStores(val uploads: ArtifactUploadsRepository, val chunks: UploadChunks, val gate: PublicationGate)

/** [UploadArtifactUseCase]. */
internal class UploadArtifactUseCaseImpl(
    private val artifacts: ArtifactsRepository,
    private val stores: UploadStores,
    private val contents: ArtifactContents,
    private val digest: ContentDigest,
    private val clock: Clock,
    private val events: ArtifactEvents,
) : UploadArtifactUseCase {
    private val rules = ArtifactAccessRules(artifacts)
    private val uploads = stores.uploads

    override suspend fun open(caller: ArtifactCaller, artifactId: Uuid?, files: List<AnnouncedFile>): ArtifactUpload {
        contents.requireActive(caller.orgId)
        val announced = announced(files)
        val now = clock.now()
        val existing = artifactId?.let { rules.owned(caller, it) }
        // A new artifact counts in `artifactsPerMember` from its draft on: TeamCode checks it here and at its commit.
        if (existing == null) {
            stores.gate.withinQuotas(caller.orgId, caller.memberId, 0, newArtifact = true)
        } else {
            stores.gate.requireIdle(caller.orgId, existing.id)
        }
        val target = existing ?: draftOf(caller, now)
        val draft = target.takeIf { existing == null }
        val held = if (existing == null) emptySet() else artifacts.blobDigests(caller.orgId, existing.id)
        val upload = ArtifactUpload(
            id = Uuid.random(),
            orgId = caller.orgId,
            artifactId = target.id,
            memberId = caller.memberId,
            credentialId = caller.credentialId,
            files = announced.map { file ->
                val state = if (file.sha256 in held) UploadBlobState.HELD else UploadBlobState.WAITING
                UploadBlob(file.sha256, file.size, state)
            },
            createdAt = now,
            expiresAt = now + UploadLimits.LIFETIME,
        )
        if (!uploads.open(upload, draft, now)) throw ArtifactUploadsBusyException()
        return upload
    }

    override suspend fun chunk(
        caller: ArtifactCaller,
        uploadId: Uuid,
        sha256: String,
        index: Int,
        bytes: ByteArray,
    ): ChunkReceipt {
        contents.requireActive(caller.orgId)
        val upload = uploads.get(caller.orgId, uploadId)?.takeIf { it.isOpenFor(caller, clock.now()) }
            ?: throw ArtifactUploadNotFoundException()
        val file = upload.files.firstOrNull { it.sha256 == sha256 && it.state != UploadBlobState.HELD }
            ?: throw ArtifactUploadNotFoundException()
        val total = UploadLimits.chunks(file.size)
        if (file.state == UploadBlobState.STORED) return ChunkReceipt(true, total, total)
        ensureValid(index in 0 until total && bytes.size.toLong() == UploadLimits.chunkSize(file.size, index)) {
            "Chunk $index does not fit this file: it has $total chunks of ${UploadLimits.CHUNK_BYTES} bytes, the " +
                "last one holding the rest."
        }
        stores.chunks.write(caller.orgId, upload.id, sha256, index, bytes)
        val received = uploads.receive(caller.orgId, upload.id, sha256, index)
        val complete = received == total && uploads.claim(caller.orgId, upload.id, sha256) &&
            assemble(caller, upload, file, total)
        return ChunkReceipt(complete, received, total)
    }

    // The file from its chunks, checked, scanned and stored in the artifact's folder. Its chunks then leave the bucket;
    // unless it is stored they are forgotten too, so that it can be sent again, whatever stopped it (the bucket, a
    // mismatch, a cancellation).
    private suspend fun assemble(
        caller: ArtifactCaller,
        upload: ArtifactUpload,
        file: UploadBlob,
        total: Int,
    ): Boolean {
        var stored = false
        try {
            val bytes = stores.chunks.read(caller.orgId, upload.id, file.sha256, total)
            if (bytes == null || bytes.size.toLong() != file.size || digest.sha256(bytes) != file.sha256) {
                throw ArtifactBlobMismatchException()
            }
            refuseUnsafe(caller, upload, file, bytes)
            contents.write(caller.orgId, upload.artifactId, mapOf(file.sha256 to bytes))
            uploads.stored(caller.orgId, upload, file.sha256, file.size)
            stored = true
        } finally {
            withContext(NonCancellable) {
                if (!stored) uploads.retry(caller.orgId, upload.id, file.sha256)
                stores.chunks.drop(caller.orgId, upload.id, file.sha256, total)
            }
        }
        return true
    }

    // A credential in a text file, or a binary file over its bound, ends the upload before anything reaches the
    // artifact's folder; a new artifact's draft that no other upload uses goes with it, so retries never use up the
    // uploads and drafts a member may hold.
    private suspend fun refuseUnsafe(
        caller: ArtifactCaller,
        upload: ArtifactUpload,
        file: UploadBlob,
        bytes: ByteArray,
    ) {
        val text = isText(bytes)
        val secret = text && ArtifactRules.holdsSecret(bytes)
        if (!secret && (text || bytes.size <= UploadLimits.MAX_BINARY_BYTES)) return
        val reason = if (secret) "secret" else "file_too_large"
        events.record(caller, PUBLISH_REFUSED, upload.artifactId, mapOf("reason" to reason, "sha256" to file.sha256))
        if (uploads.end(caller.orgId, upload)) contents.remove(caller.orgId, upload.artifactId)
        throw if (secret) ArtifactSecretDetectedException(file.sha256) else ArtifactFileTooLargeException()
    }

    private fun announced(files: List<AnnouncedFile>): List<AnnouncedFile> {
        ensureValid(files.size in 1..UploadLimits.MAX_FILES) {
            "An upload announces 1 to ${UploadLimits.MAX_FILES} files."
        }
        files.forEach { file ->
            ensureValid(SHA256.matches(file.sha256) && file.size >= 0) {
                "Each file is a sha256 of 64 lower-case hexadecimal characters and a size from 0."
            }
            if (file.size > UploadLimits.MAX_TEXT_BYTES) throw ArtifactFileTooLargeException()
        }
        val distinct = files.distinct()
        ensureValid(distinct.map { it.sha256 }.toSet().size == distinct.size) {
            "One SHA-256 is announced with two sizes."
        }
        return distinct
    }

    private fun draftOf(caller: ArtifactCaller, now: Instant) = Artifact(
        id = Uuid.random(),
        orgId = caller.orgId,
        ownerId = caller.memberId,
        title = "",
        access = ArtifactAccess.PERSONAL,
        latestVersion = 0,
        linkSlug = null,
        createdAt = now,
        updatedAt = now,
    )
}

private val SHA256 = Regex("[0-9a-f]{64}")
private const val PUBLISH_REFUSED = "publish_refused"

// UTF-8 without a NUL byte is text, scanned for credentials; anything else is binary.
private fun isText(bytes: ByteArray): Boolean = 0.toByte() !in bytes && try {
    bytes.decodeToString(throwOnInvalidSequence = true)
    true
} catch (_: CharacterCodingException) {
    false
}
