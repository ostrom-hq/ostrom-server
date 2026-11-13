// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.storage

import run.ostrom.server.artifacts.domain.ArtifactFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * The files of an organization's artifacts, in its bucket and never in PostgreSQL (TeamCode's object store): one
 * object per distinct content of an artifact, `<prefix>/<org>/<artifact>/blobs/<sha256>`. Artifacts work only while
 * a bucket is stored, its current version passed a test and an administrator turned them on ([ArtifactAvailability]).
 */
interface ArtifactContents {
    /**
     * Refuses while artifacts are inactive.
     *
     * @throws ArtifactsInactiveException With the reason.
     */
    suspend fun requireActive(orgId: Uuid)

    /**
     * Whether artifacts work.
     *
     * @return True when they do.
     */
    suspend fun active(orgId: Uuid): Boolean

    /**
     * Writes the contents of an artifact, each under its SHA-256.
     *
     * @param blobs The contents by SHA-256.
     *
     * @throws ArtifactsInactiveException When the bucket cannot be opened.
     * @throws ArtifactStorageUnavailableException When the bucket refuses one.
     */
    suspend fun write(orgId: Uuid, artifactId: Uuid, blobs: Map<String, ByteArray>)

    /**
     * Reads a file's content.
     *
     * @return Its bytes.
     *
     * @throws ArtifactsInactiveException When the bucket cannot be opened.
     * @throws ArtifactStorageUnavailableException When the bucket does not answer, lacks it or answers another size.
     */
    suspend fun read(orgId: Uuid, artifactId: Uuid, file: ArtifactFile): ByteArray

    /**
     * Removes every object of an artifact, as far as the bucket answers: what remains is collected later.
     *
     * @return True when its folder was emptied.
     */
    suspend fun remove(orgId: Uuid, artifactId: Uuid): Boolean
}

/**
 * [ArtifactContents] on the organization's bucket.
 *
 * @param storage The bucket and the settings.
 * @param objects The bucket's operations.
 */
class BucketArtifactContents(
    private val storage: ArtifactStorageRepository,
    private val objects: ArtifactObjectStore,
) : ArtifactContents {
    override suspend fun requireActive(orgId: Uuid) {
        storage.overview(orgId).availability.reason?.let { throw ArtifactsInactiveException(it) }
    }

    override suspend fun active(orgId: Uuid): Boolean = storage.overview(orgId).availability.active

    override suspend fun write(orgId: Uuid, artifactId: Uuid, blobs: Map<String, ByteArray>) {
        val connection = connection(orgId)
        for ((sha256, bytes) in blobs) {
            bucket("The artifact storage did not accept a file. Publish again.") {
                objects.put(connection, key(connection, orgId, artifactId, sha256), bytes, OCTET_STREAM)
            }
        }
    }

    override suspend fun read(orgId: Uuid, artifactId: Uuid, file: ArtifactFile): ByteArray {
        val connection = connection(orgId)
        val stored = bucket("The artifact storage did not answer. Try again.") {
            objects.get(connection, key(connection, orgId, artifactId, file.sha256))
        } ?: throw ArtifactStorageUnavailableException("The file is missing from the artifact storage.")
        if (stored.bytes.size.toLong() != file.size) {
            throw ArtifactStorageUnavailableException("The artifact storage answered an unexpected size.")
        }
        return stored.bytes
    }

    override suspend fun remove(orgId: Uuid, artifactId: Uuid): Boolean {
        val connection = storage.connection(orgId) ?: return false
        val root = ArtifactKeys.artifactRoot(connection.prefix, orgId.toString(), artifactId.toString())
        return try {
            var token: String? = null
            var emptied = true
            do {
                val page = objects.list(connection, root, token)
                if (page.keys.isNotEmpty()) emptied = objects.delete(connection, page.keys.map { it.key }).isEmpty()
                token = page.nextContinuationToken
            } while (token != null && emptied)
            emptied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ObjectStoreException) {
            false
        }
    }

    private suspend fun connection(orgId: Uuid): ArtifactStorageConnection =
        storage.connection(orgId) ?: throw ArtifactsInactiveException(InactiveReason.STORAGE_MISSING)

    private fun key(connection: ArtifactStorageConnection, orgId: Uuid, artifactId: Uuid, sha256: String) =
        ArtifactKeys.blob(connection.prefix, orgId.toString(), artifactId.toString(), sha256)

    private suspend fun <T> bucket(failure: String, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: ObjectStoreException) {
        throw ArtifactStorageUnavailableException(failure)
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}
