// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.storage

import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/**
 * The chunks of an upload, in the organization's bucket until their file is assembled (TeamCode keeps them in its
 * database; here no artifact byte is ever in PostgreSQL), under [ArtifactKeys.chunk].
 */
interface UploadChunks {
    /**
     * Writes a chunk (again: replaced).
     *
     * @throws ArtifactsInactiveException When no bucket is stored.
     * @throws ArtifactStorageUnavailableException When the bucket refuses it.
     */
    suspend fun write(orgId: Uuid, uploadId: Uuid, sha256: String, index: Int, bytes: ByteArray)

    /**
     * Reads the [count] chunks of a file back, in order.
     *
     * @return The file, or null when a chunk is missing.
     *
     * @throws ArtifactsInactiveException When no bucket is stored.
     * @throws ArtifactStorageUnavailableException When the bucket does not answer.
     */
    suspend fun read(orgId: Uuid, uploadId: Uuid, sha256: String, count: Int): ByteArray?

    /** Deletes the chunks of a file, as far as the bucket answers: what remains goes with the upload's folder. */
    suspend fun drop(orgId: Uuid, uploadId: Uuid, sha256: String, count: Int)

    /**
     * Deletes the upload's folder (`<org>/uploads/<id>/`), once it expired.
     *
     * @return True when it is empty, or no bucket is stored any more; false when the bucket failed (tried again later).
     */
    suspend fun discard(orgId: Uuid, uploadId: Uuid): Boolean
}

/**
 * [UploadChunks] in the organization's bucket.
 *
 * @param storage The bucket.
 * @param objects The bucket's operations.
 */
class BucketUploadChunks(
    private val storage: ArtifactStorageRepository,
    private val objects: ArtifactObjectStore,
) : UploadChunks {
    override suspend fun write(orgId: Uuid, uploadId: Uuid, sha256: String, index: Int, bytes: ByteArray) {
        val connection = storage.openConnection(orgId)
        bucket("The artifact storage did not accept the chunk. Send it again.") {
            objects.put(connection, key(connection, orgId, uploadId, sha256, index), bytes, OCTET_STREAM)
        }
    }

    override suspend fun read(orgId: Uuid, uploadId: Uuid, sha256: String, count: Int): ByteArray? {
        val connection = storage.openConnection(orgId)
        val parts = mutableListOf<ByteArray>()
        for (index in 0 until count) {
            val part = bucket("The artifact storage did not answer. Send the file again.") {
                objects.get(connection, key(connection, orgId, uploadId, sha256, index))
            } ?: return null
            parts += part.bytes
        }
        val file = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(file, at)
            at += part.size
        }
        return file
    }

    override suspend fun drop(orgId: Uuid, uploadId: Uuid, sha256: String, count: Int) {
        val connection = storage.connection(orgId) ?: return
        try {
            objects.delete(connection, (0 until count).map { key(connection, orgId, uploadId, sha256, it) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ObjectStoreException) {
            // Left for the collection of the upload's folder.
        }
    }

    override suspend fun discard(orgId: Uuid, uploadId: Uuid): Boolean {
        val connection = storage.connection(orgId) ?: return true
        return objects.empty(connection, ArtifactKeys.upload(connection.prefix, orgId.toString(), uploadId.toString()))
    }

    private fun key(connection: ArtifactStorageConnection, orgId: Uuid, uploadId: Uuid, sha256: String, index: Int) =
        ArtifactKeys.chunk(connection.prefix, orgId.toString(), uploadId.toString(), sha256, index)
}
