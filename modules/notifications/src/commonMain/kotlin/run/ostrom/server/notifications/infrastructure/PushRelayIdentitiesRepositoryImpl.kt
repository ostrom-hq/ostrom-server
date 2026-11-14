// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/database/SqlRelayIdentityStore.kt; see NOTICE.
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.notifications.domain.PushRelayIdentitiesRepository
import run.ostrom.server.notifications.domain.PushRelayIdentity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

/**
 * [PushRelayIdentitiesRepository] on PostgreSQL (`notifications_push_relay_instances`, the instance's, no
 * organization): one row per relay URL, the first replica to insert it wins and the others read it.
 *
 * @param database The database.
 * @param clock The creation date.
 */
class PushRelayIdentitiesRepositoryImpl(
    private val database: JdbcDatabase,
    private val clock: Clock,
) : PushRelayIdentitiesRepository {
    override suspend fun find(relayUrl: String): PushRelayIdentity? = database.query { read(relayUrl) }

    override suspend fun create(relayUrl: String, identity: PushRelayIdentity): PushRelayIdentity = database.query {
        val now = clock.now()
        PushRelayInstances.insertIgnore {
            it[PushRelayInstances.relayUrl] = relayUrl
            it[publicKey] = identity.publicKey
            it[privateKey] = identity.sealedPrivateKey
            it[instanceId] = identity.instanceId
            it[createdAt] = now
        }
        checkNotNull(read(relayUrl)) { "The relay identity was neither kept nor found." }
    }

    override suspend fun setInstance(relayUrl: String, publicKey: String, instanceId: String?) {
        database.query {
            PushRelayInstances.update({
                (PushRelayInstances.relayUrl eq relayUrl) and (PushRelayInstances.publicKey eq publicKey)
            }) { it[PushRelayInstances.instanceId] = instanceId }
        }
    }

    private fun JdbcTransaction.read(relayUrl: String): PushRelayIdentity? = PushRelayInstances
        .select(PushRelayInstances.publicKey, PushRelayInstances.privateKey, PushRelayInstances.instanceId)
        .where { PushRelayInstances.relayUrl eq relayUrl }
        .singleOrNull()
        ?.let {
            PushRelayIdentity(
                it[PushRelayInstances.publicKey],
                it[PushRelayInstances.privateKey],
                it[PushRelayInstances.instanceId],
            )
        }
}
