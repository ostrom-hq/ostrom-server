// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.fakes

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
import run.ostrom.server.artifacts.domain.storage.StoredObject
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A cipher whose envelope names its context, so one opened under another context is refused. */
object TestCipher : SecretCipher {
    override suspend fun seal(plaintext: ByteArray, context: String): String = "$context|" + Base64.encode(plaintext)

    override suspend fun open(envelope: String, context: String): ByteArray? =
        envelope.takeIf { it.startsWith("$context|") }?.let { Base64.decode(it.substringAfter('|')) }
}

/** The bucket and settings in memory, with the secret kept apart as the real one seals it. */
class InMemoryStorage(var inUse: Boolean = false) : ArtifactStorageRepository {
    val storage = mutableMapOf<Uuid, ArtifactStorage>()
    val secrets = mutableMapOf<Uuid, String>()
    val settings = mutableMapOf<Uuid, ArtifactSettings>()

    override suspend fun overview(orgId: Uuid): ArtifactStorageOverview =
        ArtifactStorageOverview(storage[orgId], settings[orgId] ?: ArtifactSettings(), inUse)

    override suspend fun saveStorage(
        orgId: Uuid,
        expectedVersion: Int,
        input: ArtifactStorageInput,
        now: Instant,
    ): ArtifactStorage? {
        if ((storage[orgId]?.version ?: 0) != expectedVersion) return null
        input.secretAccessKey?.let { secrets[orgId] = it }
        val saved = ArtifactStorage(
            orgId, input.endpoint, input.region, input.bucket, input.prefix, input.pathStyle, input.accessKeyId,
            input.serverSideEncryption, null, expectedVersion + 1, now,
        )
        storage[orgId] = saved
        return saved
    }

    override suspend fun deleteStorage(orgId: Uuid, now: Instant): Boolean {
        settings[orgId]?.let { settings[orgId] = it.copy(enabled = false, version = it.version + 1) }
        secrets.remove(orgId)
        return storage.remove(orgId) != null
    }

    override suspend fun recordTest(orgId: Uuid, version: Int, passed: Boolean, now: Instant): Boolean {
        val current = storage[orgId]?.takeIf { it.version == version } ?: return false
        storage[orgId] = current.copy(verifiedAt = now.takeIf { passed })
        return true
    }

    override suspend fun saveSettings(orgId: Uuid, settings: ArtifactSettings, now: Instant): ArtifactSettings? {
        if ((this.settings[orgId]?.version ?: 0) != settings.version) return null
        val saved = settings.copy(version = settings.version + 1, updatedAt = now)
        this.settings[orgId] = saved
        return saved
    }

    override suspend fun connection(orgId: Uuid): ArtifactStorageConnection? {
        val stored = storage[orgId]
        val secret = secrets[orgId]
        if (stored == null || secret == null) return null
        return ArtifactStorageConnection(
            stored.endpoint, stored.region, stored.bucket, stored.prefix, stored.pathStyle, stored.accessKeyId, secret,
            stored.serverSideEncryption, stored.version,
        )
    }
}

/**
 * A bucket in memory. [beforeEach] runs before every call (to fail or wait one), [keepDeleted] keeps what a deletion
 * names, [corrupt] reads other bytes back, [hideListing] lists nothing, [refuseDelete] reports every key undeleted.
 */
class InMemoryBucket : ArtifactObjectStore {
    val objects = mutableMapOf<String, ByteArray>()
    val calls = mutableListOf<String>()
    var beforeEach: suspend (String) -> Unit = {}
    var keepDeleted = false
    var corrupt = false
    var hideListing = false
    var refuseDelete = false
    var forget = false

    override suspend fun put(
        connection: ArtifactStorageConnection,
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        record("put")
        if (!forget) objects[key] = bytes
    }

    override suspend fun get(
        connection: ArtifactStorageConnection,
        key: String,
        range: LongRange?,
        maxBytes: Int,
    ): StoredObject? {
        record("get")
        val bytes = objects[key] ?: return null
        val read = if (corrupt) bytes + 1 else bytes
        return StoredObject(read, read.size.toLong(), null, null)
    }

    override suspend fun head(connection: ArtifactStorageConnection, key: String): ObjectHead? {
        record("head")
        return objects[key]?.let { ObjectHead(it.size.toLong(), null) }
    }

    override suspend fun list(
        connection: ArtifactStorageConnection,
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): ObjectListing {
        record("list")
        val keys = if (hideListing) emptyMap() else objects.filterKeys { it.startsWith(prefix) }
        return ObjectListing(keys.map { (key, bytes) -> ListedObject(key, bytes.size.toLong()) }, null)
    }

    override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> {
        record("delete")
        if (refuseDelete) return keys
        if (!keepDeleted) keys.forEach { objects.remove(it) }
        return emptyList()
    }

    private suspend fun record(call: String) {
        calls += call
        beforeEach(call)
    }
}
