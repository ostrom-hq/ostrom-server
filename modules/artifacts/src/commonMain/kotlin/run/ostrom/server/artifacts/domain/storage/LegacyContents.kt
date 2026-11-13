// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.storage

import run.ostrom.server.artifacts.domain.ArtifactsRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Moves the contents an organization published before artifacts moved to its bucket. */
fun interface LegacyContents {
    /**
     * Moves every content still in PostgreSQL to the bucket: each is written, read back and compared by SHA-256
     * before its bytes are forgotten, so a failure leaves it where it was, to move again.
     *
     * @throws ArtifactsInactiveException When the bucket cannot be opened.
     * @throws ArtifactStorageUnavailableException When the bucket refuses one or gives back other bytes.
     */
    suspend fun moveToBucket(orgId: Uuid)
}

/**
 * [LegacyContents] in batches.
 *
 * @param repository The artifacts' rows.
 * @param storage The bucket.
 * @param objects The bucket's operations.
 * @param digest SHA-256 as stored.
 * @param batch The contents read at once.
 */
class LegacyContentsMover(
    private val repository: ArtifactsRepository,
    private val storage: ArtifactStorageRepository,
    private val objects: ArtifactObjectStore,
    private val digest: suspend (ByteArray) -> String,
    private val batch: Int = BATCH,
) : LegacyContents {
    override suspend fun moveToBucket(orgId: Uuid) {
        val connection = storage.connection(orgId) ?: throw ArtifactsInactiveException(InactiveReason.STORAGE_MISSING)
        val org = orgId.toString()
        do {
            val pending = repository.legacyBlobs(orgId, batch)
            for (blob in pending) {
                val key = ArtifactKeys.blob(connection.prefix, org, blob.artifactId.toString(), blob.sha256)
                val back = copy(connection, key, blob.bytes)
                if (back == null || digest(back) != blob.sha256) {
                    throw ArtifactStorageUnavailableException(
                        "The existing artifacts could not be moved to the bucket. Test it, then try again.",
                    )
                }
                repository.releaseLegacyBlob(orgId, blob.artifactId, blob.sha256)
            }
        } while (pending.size == batch)
    }

    /** Writes [bytes] and reads them back, or null when the bucket fails either. */
    private suspend fun copy(connection: ArtifactStorageConnection, key: String, bytes: ByteArray): ByteArray? = try {
        objects.put(connection, key, bytes, OCTET_STREAM)
        objects.get(connection, key)?.bytes
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: ObjectStoreException) {
        null
    }

    private companion object {
        const val BATCH = 50
        const val OCTET_STREAM = "application/octet-stream"
    }
}
