// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.uploads

import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The bounds of an upload, TeamCode's: chunks of at most 1 MiB (under an ingress's 2 MiB body limit), a file of at
 * most 16 MiB of text or 15 MiB of binary, an hour to send it, 16 open uploads and 20 unpublished drafts per member.
 */
object UploadLimits {
    /** The most bytes of one chunk; every chunk but a file's last holds exactly this many. */
    const val CHUNK_BYTES: Int = 1024 * 1024

    /** A text file (UTF-8 without a NUL byte). */
    const val MAX_TEXT_BYTES: Long = 16L * CHUNK_BYTES

    /** Any other file. */
    const val MAX_BINARY_BYTES: Long = 15L * CHUNK_BYTES

    /** Distinct files one upload announces: a version's. */
    const val MAX_FILES: Int = 200

    /** Uploads a member holds open at once. */
    const val OPEN_UPLOADS: Int = 16

    /** Drafts (artifacts never committed) a member holds at once. */
    const val DRAFTS: Int = 20

    /** How long an upload stays open. */
    val LIFETIME = 1.hours

    /**
     * The chunks of a file of [size] bytes: one for an empty file.
     *
     * @return Their count.
     */
    fun chunks(size: Long): Int = maxOf(1L, (size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()

    /**
     * The bytes chunk [index] of a file of [size] bytes holds.
     *
     * @return [CHUNK_BYTES] for every chunk but the last, the rest for the last.
     */
    fun chunkSize(size: Long, index: Int): Long = minOf(CHUNK_BYTES.toLong(), size - index.toLong() * CHUNK_BYTES)
}

/**
 * A file the next version holds, announced by its content.
 *
 * @property sha256 Its lower-case hex SHA-256.
 * @property size Its size.
 */
data class AnnouncedFile(val sha256: String, val size: Long)

/** Where an announced file stands in its upload. */
enum class UploadBlobState(val wire: String) {
    /** The artifact's folder already holds it: nothing to send. */
    HELD("held"),

    /** Its chunks are awaited. */
    WAITING("waiting"),

    /** Every chunk arrived; one request assembles it. */
    ASSEMBLING("assembling"),

    /** Checked and in the artifact's folder. */
    STORED("stored"),
    ;

    /** Reads the stored names. */
    companion object {
        /**
         * Reads a stored name; anything unknown is [WAITING], which only asks for the file again.
         *
         * @return The state.
         */
        fun parse(wire: String): UploadBlobState = entries.firstOrNull { it.wire == wire } ?: WAITING
    }
}

/**
 * A file of an upload.
 *
 * @property sha256 Its SHA-256.
 * @property size Its size.
 * @property state Where it stands.
 */
data class UploadBlob(val sha256: String, val size: Long, val state: UploadBlobState)

/**
 * An upload in progress (TeamCode's `artifact_uploads`): the files of the next version of one artifact, sent in chunks
 * by the credential that opened it. No byte of it is in PostgreSQL: the chunks wait in the organization's bucket.
 *
 * @property id Its id.
 * @property orgId The organization.
 * @property artifactId The artifact, a draft when the upload created it.
 * @property memberId Who opened it.
 * @property credentialId The credential that opened it, the only one that sends its chunks.
 * @property files The distinct files of the version.
 * @property createdAt When it was opened.
 * @property expiresAt When it closes.
 */
data class ArtifactUpload(
    val id: Uuid,
    val orgId: Uuid,
    val artifactId: Uuid,
    val memberId: String,
    val credentialId: String?,
    val files: List<UploadBlob>,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    /** The files to send, in the order they were announced. */
    val missing: List<String> get() = files.filter { it.state != UploadBlobState.HELD }.map { it.sha256 }
}

/**
 * One chunk received.
 *
 * @property complete Every chunk of the file arrived and the file was checked and stored.
 * @property received The chunks of the file received so far.
 * @property total The chunks the file has.
 */
data class ChunkReceipt(val complete: Boolean, val received: Int, val total: Int)
