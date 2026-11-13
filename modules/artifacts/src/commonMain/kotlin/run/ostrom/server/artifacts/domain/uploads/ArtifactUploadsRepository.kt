// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.uploads

import run.ostrom.server.artifacts.domain.Artifact
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The uploads in progress, without their bytes (TeamCode's `artifact_uploads`, `artifact_upload_chunks`,
 * `artifact_upload_blobs`): PostgreSQL is the only bus between replicas, so a chunk may reach any of them.
 */
interface ArtifactUploadsRepository {
    /**
     * Records an upload and the draft it creates, unless the member already holds [UploadLimits.OPEN_UPLOADS] open
     * uploads or, for a draft, [UploadLimits.DRAFTS] drafts; counted under a lock on the member.
     *
     * @param draft The draft artifact to create, or null for an upload to an existing artifact.
     * @param now The moment, to count the uploads still open.
     *
     * @return False when the member holds too many.
     */
    suspend fun open(upload: ArtifactUpload, draft: Artifact?, now: Instant): Boolean

    /**
     * Reads an upload.
     *
     * @return It, or null when it does not exist.
     */
    suspend fun get(orgId: Uuid, id: Uuid): ArtifactUpload?

    /**
     * Records that chunk [index] of a file arrived (again: counted once).
     *
     * @return How many distinct chunks of the file arrived.
     */
    suspend fun receive(orgId: Uuid, uploadId: Uuid, sha256: String, index: Int): Int

    /**
     * Takes the assembly of a file whose chunks all arrived.
     *
     * @return True for the one request that assembles it.
     */
    suspend fun claim(orgId: Uuid, uploadId: Uuid, sha256: String): Boolean

    /** The file is checked and in the artifact's folder: recorded with its size, done for this upload. */
    suspend fun stored(orgId: Uuid, upload: ArtifactUpload, sha256: String, size: Long)

    /** The file could not be assembled or stored: its chunks are forgotten, to send again. */
    suspend fun retry(orgId: Uuid, uploadId: Uuid, sha256: String)

    /**
     * Ends an upload; a draft no other upload uses goes with it.
     *
     * @return True when its draft was deleted (its folder in the bucket is to be emptied).
     */
    suspend fun end(orgId: Uuid, upload: ArtifactUpload): Boolean
}
