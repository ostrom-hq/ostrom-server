// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConnection
import run.ostrom.server.artifacts.domain.storage.ObjectStoreException
import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Files sent to sessions (`runners.cloud.files.v1`, TeamCode's `CloudFileService`). An upload is authorized exactly
 * like a message ([CloudConversationService.authorizeMessage]); its bytes go to the organization's artifact bucket in
 * 1 MiB parts while the type is recognized from them; its row only holds metadata. A message names it by id; its host
 * downloads it on the runner channel, and once the message is acknowledged (or after a day) the [sweep] removes the
 * bytes, then the row.
 */
interface CloudFileService {
    /** @return Whether the caller's organization can send files now, and its limits. */
    suspend fun policy(caller: Caller): CloudFilePolicy

    /**
     * Receives a file for [sessionId]. [announcedBytes] (the `Content-Length`) refuses a file too large before any byte
     * is read. With [clientKey], a retry of the same file (same name and bytes) returns the first upload; other bytes
     * under the same key are `422 idempotency_conflict`.
     *
     * @return The file, created or replayed.
     */
    @Suppress("LongParameterList") // The upload's request, as TeamCode's.
    suspend fun upload(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        sessionId: Uuid,
        rawName: String?,
        announcedBytes: Long?,
        clientKey: String?,
        body: CloudFileBody,
    ): CloudFileOutcome

    /**
     * @return The file a runner downloads; fenced like every runner call, then `404` unless a message delivered to this
     *   execution carries it.
     * @throws ExecutionFencedException When the execution is no longer the session's live one.
     */
    suspend fun runnerFile(execution: RunnerExecution, fileId: Uuid): RunnerFileDownload

    /**
     * Marks expired files, then removes the bytes of files marked for deletion (acknowledged, expired or abandoned) and
     * forgets their rows. A file whose bytes could not be removed is retried at the next pass.
     *
     * @return The rows forgotten.
     */
    suspend fun sweep(limit: Int = SWEEP_BATCH): Int

    /** Constants of the service. */
    companion object {
        /** An upload that does not complete within this time is removed (a server that stopped in the middle). */
        const val UPLOAD_MILLIS: Long = 60 * 60_000L

        /** Why an organization cannot send files. */
        const val NOT_CONFIGURED: String = "file_storage_not_configured"

        /** Files one sweep handles. */
        const val SWEEP_BATCH: Int = 200

        /** Keys one deletion of the bucket carries. */
        const val DELETE_BATCH: Int = 1_000

        /** @return What an idempotency key stands for: the cleaned name and the bytes. */
        fun requestDigest(name: String, sha256: String, size: Long): String =
            hex("file\n$name\n$sha256\n$size".encodeToByteArray())

        /** @return The SHA-256 of [bytes], in hexadecimal. */
        fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    }
}

/**
 * [CloudFileService] on the files' store, the sessions and the organization's bucket.
 *
 * @param files The file rows.
 * @param sessions The sessions, for the runner's fence.
 * @param conversation Authorizes an upload as a message.
 * @param storage The organization's bucket.
 * @param objects The bucket's objects.
 * @param runtime The instance's cloud runtime, for the file limits; null without the cloud module (the defaults).
 * @param audit The organization's audit log.
 * @param clock The time.
 */
@Suppress("LongParameterList") // TeamCode's collaborators, plus Ostrom's runtime, audit and clock.
class CloudFileServiceImpl(
    private val files: CloudFileStore,
    private val sessions: CloudSessionsRepository,
    private val conversation: CloudConversationService,
    private val storage: CloudFileStorage,
    private val objects: ArtifactObjectStore,
    private val runtime: CloudRuntimeSource?,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : CloudFileService {
    override suspend fun policy(caller: Caller): CloudFilePolicy {
        val settings = settings()
        val available = storage.active(caller.orgId) != null
        return CloudFilePolicy(
            available,
            if (available) null else CloudFileService.NOT_CONFIGURED,
            CloudFileRules.fileLimit(settings),
            CloudFileRules.messageLimit(settings),
            CloudFileRules.MAX_FILES_PER_MESSAGE,
            CloudFileRules.ALLOWED_TYPES,
        )
    }

    override suspend fun upload(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        sessionId: Uuid,
        rawName: String?,
        announcedBytes: Long?,
        clientKey: String?,
        body: CloudFileBody,
    ): CloudFileOutcome {
        val session = conversation.authorizeMessage(caller, sessionId)
        val name = CloudFileRules.sanitizeName(rawName)
        val connection = storage.active(caller.orgId) ?: throw notConfigured()
        val limit = CloudFileRules.fileLimit(settings())
        if (announcedBytes != null && announcedBytes > limit) throw CloudFileRules.tooLarge(limit)
        val now = clock.now()
        val draft = CloudFile(
            Uuid.random(), caller.orgId, session.id, caller.memberId, principal.credentialId, name, null,
            announcedBytes ?: 0, null, clientKey, null, null, now, null,
            now + CloudFileService.UPLOAD_MILLIS.milliseconds,
        )
        val begun = files.begin(draft, session.ownerId, announcedBytes ?: 0)
        if (begun is CloudFileBegin.Existing) return replay(begun.file, name, body, limit)
        val stored = try {
            val received = receive(connection, draft, body, limit)
            val digest = clientKey?.let { CloudFileService.requestDigest(name, received.sha256, received.size) }
            val finalName = CloudFileRules.finalName(name, received.contentType)
            val size = received.size
            files.complete(caller.orgId, draft.id, finalName, received.contentType, size, received.sha256, digest)
        } catch (error: CancellationException) {
            // Whatever ended the upload (a refusal, the client leaving, the bucket), the sweep removes its parts.
            abandon(draft)
            throw error
        } catch (error: Exception) {
            abandon(draft)
            throw error
        }
        // The name, size and type, never the content: the audit says who sent what to which session.
        val details = auditDetails(
            "sessionId" to session.id,
            "name" to stored.name,
            "size" to stored.size,
            "contentType" to stored.contentType,
        )
        audit.record(OrgAuditRecord(caller.orgId, principal, UPLOADED, SUBJECT, stored.id.toString(), details))
        return CloudFileOutcome.Created(stored)
    }

    private suspend fun abandon(draft: CloudFile) = withContext(NonCancellable) {
        try {
            files.abandon(draft.orgId, draft.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The expiry of the upload marks it for the sweep anyway.
        }
    }

    override suspend fun runnerFile(execution: RunnerExecution, fileId: Uuid): RunnerFileDownload {
        sessions.get(execution.orgId, execution.sessionId)
            ?.takeIf { it.epoch == execution.epoch && it.state.holdsExecution }
            ?.takeIf { it.desiredState != DesiredState.TERMINATED }
            ?: throw ExecutionFencedException()
        val file = files.deliverable(execution.orgId, execution.sessionId, execution.epoch, fileId) ?: throw notFound()
        val connection = storage.any(execution.orgId) ?: throw notFound()
        suspend fun part(number: Int): ByteArray {
            val key = CloudFileKeys.part(connection.prefix, file.orgId, file.sessionId, file.id, number)
            val part = try {
                objects.get(connection, key, maxBytes = CloudFileRules.PART_BYTES)
            } catch (_: ObjectStoreException) {
                throw unavailable()
            }
            return (part ?: throw gone()).bytes
        }
        // The first part before any header: a bucket that fails or bytes that are gone answer 503 or 410. A later part
        // that fails cuts the stream short of its Content-Length, and the runner's check of the hash refuses it.
        val first = part(0)
        return RunnerFileDownload(file) { emit ->
            emit(first)
            for (number in 1 until chunks(file.size)) emit(part(number))
        }
    }

    override suspend fun sweep(limit: Int): Int {
        files.expire(limit)
        var forgotten = 0
        for ((orgId, due) in files.deleting(limit).groupBy { it.orgId }) {
            val connection = storage.any(orgId)
            val removed = if (connection == null) due.map { it.id } else removeBytes(connection, due)
            files.forget(orgId, removed)
            forgotten += removed.size
        }
        files.purgeUsage(clock.now().toEpochMilliseconds() / CloudFileRules.DAY_MILLIS - 1)
        return forgotten
    }

    /** Deletes the parts of [due]; returns the files whose bytes are all gone (no bucket left: nothing to remove). */
    private suspend fun removeBytes(connection: ArtifactStorageConnection, due: List<CloudFile>): List<Uuid> {
        val removed = mutableListOf<Uuid>()
        val keys = mutableMapOf<String, Uuid>()
        for (file in due) {
            try {
                // A complete file's parts follow from its size; an unfinished upload's are listed.
                val parts = if (file.completedAt != null) {
                    (0 until chunks(file.size)).map {
                        CloudFileKeys.part(connection.prefix, file.orgId, file.sessionId, file.id, it)
                    }
                } else {
                    val root = CloudFileKeys.root(connection.prefix, file.orgId, file.sessionId, file.id)
                    objects.list(connection, root).keys.map { it.key }
                }
                parts.forEach { keys[it] = file.id }
                if (parts.isEmpty()) removed += file.id
            } catch (_: ObjectStoreException) {
                // Listed again at the next pass.
            }
        }
        val failed = mutableSetOf<Uuid>()
        for (batch in keys.keys.chunked(CloudFileService.DELETE_BATCH)) {
            val left = try {
                objects.delete(connection, batch)
            } catch (_: ObjectStoreException) {
                batch
            }
            left.mapNotNullTo(failed) { keys[it] }
        }
        removed += keys.values.toSet() - failed
        return removed.distinct()
    }

    private class Received(val size: Long, val sha256: String, val contentType: String)

    @Suppress("ThrowsCount") // Too large, not an allowed type, the bucket and empty: one answer each.
    private suspend fun receive(
        connection: ArtifactStorageConnection,
        file: CloudFile,
        body: CloudFileBody,
        limit: Long,
    ): Received {
        val digest = MessageDigest.getInstance("SHA-256")
        val text = CloudFileRules.TextCheck()
        var size = 0L
        var number = 0
        var binary: String? = null
        while (true) {
            val part = body.next(CloudFileRules.PART_BYTES) ?: break
            size += part.size
            if (size > limit) throw CloudFileRules.tooLarge(limit)
            // The first part holds the whole head: the body is read in full parts until its end.
            if (number == 0) binary = CloudFileRules.magicType(part)
            if (binary == null) {
                text.feed(part)
                if (!text.valid) throw CloudFileRules.typeNotAllowed()
            }
            digest.update(part)
            val key = CloudFileKeys.part(connection.prefix, file.orgId, file.sessionId, file.id, number)
            try {
                objects.put(connection, key, part, OCTET_STREAM)
            } catch (_: ObjectStoreException) {
                throw unavailable()
            }
            number++
        }
        if (size == 0L) throw CloudConversationRules.invalid("empty_file", "The file is empty.")
        val contentType = binary ?: if (text.finish()) CloudFileRules.textType(file.name) else throw typeNotAllowed()
        return Received(size, digest.digest().toHexString(), contentType)
    }

    /** A retry under the same key: the bytes are read again (not stored) and must be the same file. */
    @Suppress("ThrowsCount") // Still uploading, too large and other bytes: one answer each.
    private suspend fun replay(existing: CloudFile, name: String, body: CloudFileBody, limit: Long): CloudFileOutcome {
        if (existing.completedAt == null) {
            throw CloudConversationRules.conflict(
                "file_upload_in_progress",
                "A file with this idempotency key is still being uploaded.",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        while (true) {
            val part = body.next(CloudFileRules.PART_BYTES) ?: break
            size += part.size
            if (size > limit) throw CloudFileRules.tooLarge(limit)
            digest.update(part)
        }
        if (existing.requestDigest != CloudFileService.requestDigest(name, digest.digest().toHexString(), size)) {
            throw CloudConversationException(
                ErrorKind.UNPROCESSABLE,
                "idempotency_conflict",
                "This idempotency key was already used with a different file.",
            )
        }
        return CloudFileOutcome.Replayed(existing)
    }

    private suspend fun settings(): CloudRuntime = if (runtime == null) CloudRuntime() else runtime.current()

    private companion object {
        const val UPLOADED = "cloud.file.uploaded"
        const val SUBJECT = "cloud_file"
        const val OCTET_STREAM = "application/octet-stream"

        fun chunks(size: Long): Int = ((size + CloudFileRules.PART_BYTES - 1) / CloudFileRules.PART_BYTES).toInt()

        fun typeNotAllowed() = CloudFileRules.typeNotAllowed()

        fun notConfigured() = CloudConversationRules.conflict(
            CloudFileService.NOT_CONFIGURED,
            "This organization has no tested bucket for files. An administrator sets one up in the console's " +
                "artifact storage.",
        )

        fun unavailable() = CloudConversationException(
            ErrorKind.UNAVAILABLE,
            "file_storage_unavailable",
            "The organization's bucket did not answer. Send the file again.",
        )

        fun notFound() = CloudConversationException(ErrorKind.NOT_FOUND, "not_found", "File not found.")

        fun gone() = CloudConversationException(ErrorKind.GONE, "file_expired", "The file's bytes are gone.")
    }
}
