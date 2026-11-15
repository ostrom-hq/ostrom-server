// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance.infrastructure

import run.ostrom.server.instance.domain.Instance
import run.ostrom.server.instance.domain.InstanceRepository
import run.ostrom.server.instance.domain.KeyRotation
import run.ostrom.server.instance.domain.SigningKey
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.present
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * [InstanceRepository] on PostgreSQL (tables of [InstanceMigrations]).
 *
 * @param database The serving role's pool.
 */
class InstanceRepositoryImpl(private val database: JdbcDatabase) : InstanceRepository {
    override suspend fun get(): Instance? = database.query { instance().singleOrNull()?.toInstance() }

    override suspend fun createIfAbsent(instance: Instance): Instance = database.query {
        InstanceInfo.insertIgnore {
            it[singleton] = true
            it[id] = instance.id
            it[createdAt] = instance.createdAt
        }
        // The row exists now, ours or the one another replica wrote first.
        instance().single().toInstance()
    }

    override suspend fun registerKey(key: SigningKey) {
        database.query {
            val k = InstanceSigningKeys
            // A key's first rotation is kept: a later one never replaces it.
            k.upsert(
                k.keyId,
                onUpdate = {
                    it[k.rotationKeyId] = insertValue(k.rotationKeyId)
                    it[k.rotationSignature] = insertValue(k.rotationSignature)
                },
                where = { k.rotationKeyId.isNull() },
            ) {
                it[keyId] = key.keyId
                it[algorithm] = key.algorithm
                it[publicKeyPem] = key.publicKeyPem
                it[firstSeenAt] = key.firstSeenAt
                it[rotationKeyId] = key.rotation?.keyId
                it[rotationSignature] = key.rotation?.signature
            }
        }
    }

    override suspend fun listKeys(): List<SigningKey> = database.query {
        val k = InstanceSigningKeys
        k.select(k.columns)
            .orderBy(k.firstSeenAt to SortOrder.ASC, k.keyId to SortOrder.ASC)
            .limit(MAX_KEYS)
            .map { it.toSigningKey() }
    }

    private fun instance() = InstanceInfo.select(InstanceInfo.id, InstanceInfo.createdAt)

    private fun ResultRow.toInstance() = Instance(id = this[InstanceInfo.id], createdAt = this[InstanceInfo.createdAt])

    private fun ResultRow.toSigningKey() = SigningKey(
        keyId = this[InstanceSigningKeys.keyId],
        algorithm = this[InstanceSigningKeys.algorithm],
        publicKeyPem = this[InstanceSigningKeys.publicKeyPem],
        firstSeenAt = this[InstanceSigningKeys.firstSeenAt],
        rotation = this[InstanceSigningKeys.rotationKeyId]?.let {
            KeyRotation(it, present(InstanceSigningKeys.rotationSignature, InstanceSigningKeys.rotationKeyId))
        },
    )

    private companion object {
        /** A key is added at each rotation of the signing key, a few a year: the bound of the list, far above. */
        const val MAX_KEYS = 1_000
    }
}
