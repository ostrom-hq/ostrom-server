// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `POST /api/v1/artifacts/uploads` (`createArtifactUpload`): announce the files of the next version. */
@Serializable
@Resource("/api/v1/artifacts/uploads")
class ArtifactUploadsApi {
    /**
     * `PUT /api/v1/artifacts/uploads/{upload}/blobs/{sha256}/{n}` (`putArtifactUploadChunk`): one chunk of a file.
     *
     * @property parent The uploads.
     * @property upload The upload.
     * @property sha256 The file.
     * @property n The chunk, from 0.
     */
    @Serializable
    @Resource("{upload}/blobs/{sha256}/{n}")
    class Chunk(
        val parent: ArtifactUploadsApi = ArtifactUploadsApi(),
        val upload: String,
        val sha256: String,
        val n: String,
    )
}

/**
 * A file of the next version, by its content.
 *
 * @property sha256 Its lower-case hex SHA-256.
 * @property size Its size.
 */
@Serializable
data class ArtifactFileRefDto(val sha256: String, val size: Long)

/**
 * Opens an upload.
 *
 * @property artifactId The artifact; a draft is created without it.
 * @property files The distinct files of the version.
 */
@Serializable
data class CreateArtifactUploadRequest(val artifactId: String? = null, val files: List<ArtifactFileRefDto>)

/**
 * An upload opened.
 *
 * @property uploadId Its id.
 * @property artifactId The artifact (the draft it created, when none was named).
 * @property missing The SHA-256 values to send.
 * @property chunkBytes The most bytes of a chunk.
 * @property expiresAt When it closes.
 */
@Serializable
data class ArtifactUploadResponse(
    val uploadId: String,
    val artifactId: String,
    val missing: List<String>,
    val chunkBytes: Int,
    val expiresAt: String,
)

/**
 * A chunk received.
 *
 * @property complete The file arrived whole, was checked and stored.
 * @property received Its chunks received so far.
 * @property total The chunks it has.
 */
@Serializable
data class ArtifactUploadChunkResponse(val complete: Boolean, val received: Int, val total: Int)
