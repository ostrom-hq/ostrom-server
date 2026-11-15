// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConnection
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Files sent to sessions (`runners.cloud.files.v1`, TeamCode's CloudFiles.kt): their metadata, the store of their
// rows, the organization's bucket they live in and the layout of their objects.

/**
 * A file a member sent to a session, kept in the organization's bucket until the session's host received it (or for a
 * day). [contentType], [sha256] and [completedAt] are null while it is being uploaded; [size] is then the announced
 * size, or 0. [inputId] is the message that carries it to the agent; [deletingSince] says its bytes are being removed.
 */
data class CloudFile(
    val id: Uuid,
    val orgId: Uuid,
    val sessionId: Uuid,
    val authorId: String,
    val credentialId: String?,
    val name: String,
    val contentType: String?,
    val size: Long,
    val sha256: String?,
    val clientKey: String?,
    val requestDigest: String?,
    val inputId: Uuid?,
    val createdAt: Instant,
    val completedAt: Instant?,
    val expiresAt: Instant,
    val consumedAt: Instant? = null,
    val deletingSince: Instant? = null,
)

/** What a message tells its host about a file: enough to download it, check it and name it. */
data class CloudFileRef(val id: Uuid, val name: String, val size: Long, val contentType: String, val sha256: String)

/** @return What a message tells its host about this file, once complete (its type and hash are known then). */
fun CloudFile.toRef(): CloudFileRef = CloudFileRef(id, name, size, contentType.orEmpty(), sha256.orEmpty())

/** What starting an upload found. */
sealed interface CloudFileBegin {
    /** A new upload. */
    data object Started : CloudFileBegin

    /** The idempotency key names a file already uploaded (or being uploaded) by this author to this session. */
    data class Existing(val file: CloudFile) : CloudFileBegin
}

/** What an upload did. */
sealed interface CloudFileOutcome {
    /** The file. */
    val file: CloudFile

    /** A new file. */
    data class Created(override val file: CloudFile) : CloudFileOutcome

    /** The first file of this idempotency key. */
    data class Replayed(override val file: CloudFile) : CloudFileOutcome
}

/**
 * Whether the caller's organization can receive files now, and its limits.
 *
 * @property reason Why not, `file_storage_not_configured`; null when it can.
 */
data class CloudFilePolicy(
    val available: Boolean,
    val reason: String?,
    val maxFileBytes: Long,
    val maxMessageBytes: Long,
    val maxFilesPerMessage: Int,
    val types: List<String>,
)

/** The request body, read in parts: [next] returns up to `max` bytes (fewer only at the end), null once exhausted. */
fun interface CloudFileBody {
    /** @return The next part, or null at the end. */
    suspend fun next(max: Int): ByteArray?
}

/**
 * A file the runner may download, and its bytes part by part.
 *
 * @property parts Emits each part in order.
 */
class RunnerFileDownload(val file: CloudFile, val parts: suspend (emit: suspend (ByteArray) -> Unit) -> Unit)

/** The organization's bucket, as artifacts use it (the artifacts module's, bound in the composition root). */
interface CloudFileStorage {
    /** @return The bucket only when its current version passed a connection test; null otherwise. */
    suspend fun active(orgId: Uuid): ArtifactStorageConnection?

    /** @return Any stored bucket, tested or not, so files can still be removed after the bucket failed a test. */
    suspend fun any(orgId: Uuid): ArtifactStorageConnection?
}

/** Without the artifacts module there is no bucket: files are not available. */
object NoCloudFileStorage : CloudFileStorage {
    override suspend fun active(orgId: Uuid): ArtifactStorageConnection? = null

    override suspend fun any(orgId: Uuid): ArtifactStorageConnection? = null
}

/**
 * Files of sessions in PostgreSQL (TeamCode's `CloudFileStore`). Writes that count a session's files lock the session
 * row; every read and write is confined to the file's organization.
 */
interface CloudFileStore {
    /**
     * Under the session row lock: refuses a session that ended or changed owner since [expectedOwnerId], returns the
     * file of the same author, session and idempotency key when there is one, then checks the session's buffer and
     * unsent files and the author's day with [announcedBytes] (`429 upload_quota`), and inserts [file].
     *
     * @return What it found.
     */
    suspend fun begin(file: CloudFile, expectedOwnerId: String, announcedBytes: Long): CloudFileBegin

    /**
     * Under the session row lock again: rechecks the quotas with the real [size], completes the file and counts it in
     * the author's day.
     *
     * @return The file as completed.
     * @throws CloudConversationException `file_expired` when it was abandoned or swept meanwhile.
     */
    @Suppress("LongParameterList") // What the upload learned of its bytes.
    suspend fun complete(
        orgId: Uuid,
        id: Uuid,
        name: String,
        contentType: String,
        size: Long,
        sha256: String,
        requestDigest: String?,
    ): CloudFile

    /** Marks a file whose upload failed for deletion. */
    suspend fun abandon(orgId: Uuid, id: Uuid)

    /**
     * @return The file a runner of [epoch] may download: complete, not being deleted, in [sessionId], and carried by a
     *   message delivered to that very epoch and not acknowledged yet; null otherwise.
     */
    suspend fun deliverable(orgId: Uuid, sessionId: Uuid, epoch: Long, id: Uuid): CloudFile?

    /** @return How many files of every organization past their expiry it marked for deletion, at most [limit]. */
    suspend fun expire(limit: Int): Int

    /** @return Files of every organization marked for deletion, oldest mark first, at most [limit]. */
    suspend fun deleting(limit: Int): List<CloudFile>

    /** Deletes the rows of the organization's files whose bytes are gone. */
    suspend fun forget(orgId: Uuid, ids: List<Uuid>)

    /** @return How many per-member daily counters of days before [day] (days since the epoch, UTC) it deleted. */
    suspend fun purgeUsage(day: Long): Int
}

/** Object keys: `<prefix>/session-files/<org>/<session>/<file>/<part>`, apart from artifacts' `<prefix>/<org>/`. */
object CloudFileKeys {
    /** @return The folder of one file's parts. */
    fun root(prefix: String, orgId: Uuid, sessionId: Uuid, fileId: Uuid): String =
        (if (prefix.isEmpty()) "" else "$prefix/") + "session-files/$orgId/$sessionId/$fileId/"

    /** @return The key of part [number]. */
    fun part(prefix: String, orgId: Uuid, sessionId: Uuid, fileId: Uuid, number: Int): String =
        root(prefix, orgId, sessionId, fileId) + number
}
