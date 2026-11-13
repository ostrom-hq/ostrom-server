// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app.isolation

import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConnection
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageInput
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.artifacts.domain.storage.ListedObject
import run.ostrom.server.artifacts.domain.storage.ObjectHead
import run.ostrom.server.artifacts.domain.storage.ObjectListing
import run.ostrom.server.artifacts.domain.storage.ServerSideEncryption
import run.ostrom.server.artifacts.domain.storage.StoredObject
import run.ostrom.server.testing.TestServer
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The matrix's buckets, in memory and keyed by bucket then key, so the organizations publish artifacts without
 * reaching S3. Each organization has its own bucket, as in production.
 */
internal class MatrixBucket : ArtifactObjectStore {
    private val objects = mutableMapOf<Pair<String, String>, ByteArray>()

    override suspend fun put(
        connection: ArtifactStorageConnection,
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        objects[connection.bucket to key] = bytes
    }

    override suspend fun get(
        connection: ArtifactStorageConnection,
        key: String,
        range: LongRange?,
        maxBytes: Int,
    ): StoredObject? {
        val bytes = objects[connection.bucket to key] ?: return null
        val read = if (range == null) bytes else bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
        return StoredObject(read, bytes.size.toLong(), null, null)
    }

    override suspend fun head(connection: ArtifactStorageConnection, key: String): ObjectHead? =
        objects[connection.bucket to key]?.let { ObjectHead(it.size.toLong(), null) }

    override suspend fun list(
        connection: ArtifactStorageConnection,
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): ObjectListing {
        val keys = objects.filterKeys { (bucket, key) -> bucket == connection.bucket && key.startsWith(prefix) }
            .map { (at, bytes) -> ListedObject(at.second, bytes.size.toLong()) }
        return ObjectListing(keys, null)
    }

    override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> {
        keys.forEach { objects.remove(connection.bucket to it) }
        return emptyList()
    }
}

/**
 * Turns artifacts on for [orgId] as the administration does: a tested bucket (in memory, through [MatrixBucket]) and
 * the setting on. Whether the bucket was saved.
 */
internal suspend fun TestServer.activateArtifacts(orgId: Uuid, marker: String): Boolean {
    val now = Clock.System.now()
    val storage = koin.get<ArtifactStorageRepository>()
    val input = ArtifactStorageInput(
        "https://s3.example.com", "auto", "bucket-$marker", "", false, "AKIA$marker", "secret-$marker",
        ServerSideEncryption.NONE,
    )
    val saved = storage.saveStorage(orgId, 0, input, now) ?: return false
    storage.recordTest(orgId, saved.version, true, now)
    storage.saveSettings(orgId, storage.overview(orgId).settings.copy(enabled = true), now)
    return true
}
