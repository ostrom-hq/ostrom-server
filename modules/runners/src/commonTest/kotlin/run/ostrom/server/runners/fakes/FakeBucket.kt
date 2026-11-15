// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.artifacts.domain.storage.ArtifactSettings
import run.ostrom.server.artifacts.domain.storage.ArtifactStorage
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConnection
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageInput
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageOverview
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.artifacts.domain.storage.ListedObject
import run.ostrom.server.artifacts.domain.storage.ObjectHead
import run.ostrom.server.artifacts.domain.storage.ObjectListing
import run.ostrom.server.artifacts.domain.storage.ObjectStoreException
import run.ostrom.server.artifacts.domain.storage.ServerSideEncryption
import run.ostrom.server.artifacts.domain.storage.StoredObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A bucket in memory; each operation can be made to fail ([failing] holds `put`, `get`, `list`, `delete`). */
class FakeBucket : ArtifactObjectStore {
    val objects: MutableMap<String, ByteArray> = linkedMapOf()
    val failing: MutableSet<String> = mutableSetOf()

    /** Keys a deletion leaves (the bucket answers them as not deleted). */
    val stuck: MutableSet<String> = mutableSetOf()

    private fun check(operation: String) {
        if (operation in failing) throw ObjectStoreException(503, "SlowDown")
    }

    override suspend fun put(
        connection: ArtifactStorageConnection,
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        check("put")
        objects[key] = bytes
    }

    override suspend fun get(
        connection: ArtifactStorageConnection,
        key: String,
        range: LongRange?,
        maxBytes: Int,
    ): StoredObject? {
        check("get")
        return objects[key]?.let { StoredObject(it, it.size.toLong(), null, null) }
    }

    override suspend fun head(connection: ArtifactStorageConnection, key: String): ObjectHead? =
        objects[key]?.let { ObjectHead(it.size.toLong(), null) }

    override suspend fun list(
        connection: ArtifactStorageConnection,
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): ObjectListing {
        check("list")
        return ObjectListing(
            objects.filterKeys { it.startsWith(prefix) }.map { ListedObject(it.key, it.value.size.toLong()) },
            null,
        )
    }

    override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> {
        check("delete")
        val left = keys.filter { it in stuck }
        (keys - left.toSet()).forEach { objects.remove(it) }
        return left
    }
}

/**
 * An organization's bucket as the artifacts module stores it: [tested] says whether its current version passed a
 * connection test, [stored] whether there is one at all.
 */
class FakeArtifactStorage(var stored: Boolean = true, var tested: Boolean = true) : ArtifactStorageRepository {
    var version: Int = 1
    var connectionVersion: Int = 1

    /** False once the stored secret no longer opens. */
    var opens: Boolean = true

    private fun storage(orgId: Uuid) = ArtifactStorage(
        orgId, ENDPOINT, "auto", "bucket", PREFIX, true, "key", ServerSideEncryption.NONE,
        if (tested) AT else null, version, AT,
    )

    override suspend fun overview(orgId: Uuid): ArtifactStorageOverview =
        ArtifactStorageOverview(if (stored) storage(orgId) else null, ArtifactSettings(), false)

    override suspend fun saveStorage(
        orgId: Uuid,
        expectedVersion: Int,
        input: ArtifactStorageInput,
        now: Instant,
    ): ArtifactStorage? = null

    override suspend fun deleteStorage(orgId: Uuid, now: Instant): Boolean = false

    override suspend fun recordTest(orgId: Uuid, version: Int, passed: Boolean, now: Instant): Boolean = false

    override suspend fun saveSettings(orgId: Uuid, settings: ArtifactSettings, now: Instant): ArtifactSettings? = null

    override suspend fun connection(orgId: Uuid): ArtifactStorageConnection? = if (stored && opens) {
        ArtifactStorageConnection(
            ENDPOINT, "auto", "bucket", PREFIX, true, "key", "secret", ServerSideEncryption.NONE,
            connectionVersion,
        )
    } else {
        null
    }

    companion object {
        /** The bucket's key prefix. */
        const val PREFIX: String = "ostrom"
        private const val ENDPOINT = "https://bucket.example"
        private val AT = Instant.parse("2026-11-14T09:00:00Z")
    }
}
