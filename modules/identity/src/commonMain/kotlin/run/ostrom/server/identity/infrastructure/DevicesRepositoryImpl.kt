// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.DevicesRepository
import run.ostrom.server.identity.domain.MAX_REGISTERED_DEVICES
import run.ostrom.server.identity.domain.RegisteredDevice
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [DevicesRepository] on PostgreSQL through Exposed ([IdentityDevices]): an account-level table, reached outside any
 * organization. The tokens of a device are the account-level ones (`org_id IS NULL`), revoked as the serving role.
 *
 * @param database The JDBC pool.
 */
class DevicesRepositoryImpl(private val database: JdbcDatabase) : DevicesRepository {
    private val t = IdentityDevices

    override suspend fun find(userId: Uuid, installationId: String): RegisteredDevice? = database.query {
        t.select(t.columns)
            .where { (t.userId eq userId) and (t.installationId eq installationId) }
            .singleOrNull()?.toDevice()
    }

    // Counted over a page of at most MAX_REGISTERED_DEVICES rows, never every row.
    override suspend fun count(userId: Uuid): Int = database.query {
        t.select(t.id).where { t.userId eq userId }.limit(MAX_REGISTERED_DEVICES).count().toInt()
    }

    override suspend fun get(id: Uuid): RegisteredDevice? = database.query {
        t.select(t.columns).where { t.id eq id }.singleOrNull()?.toDevice()
    }

    override suspend fun getAll(ids: Collection<Uuid>): List<RegisteredDevice> =
        if (ids.isEmpty()) emptyList() else database.query {
            t.select(t.columns).where { t.id inList ids.distinct() }.map { it.toDevice() }
        }

    override suspend fun list(userId: Uuid): List<RegisteredDevice> = database.query {
        t.select(t.columns)
            .where { t.userId eq userId }
            .orderBy(t.lastLoginAt to SortOrder.DESC, t.id to SortOrder.ASC)
            .limit(MAX_REGISTERED_DEVICES)
            .map { it.toDevice() }
    }

    override suspend fun rename(id: Uuid, name: String): Boolean = database.query {
        t.update({ t.id eq id }) { it[this.name] = name } == 1
    }

    override suspend fun remove(userId: Uuid, id: Uuid, at: Instant): Boolean = database.query {
        // After any sign-in of the person under way: none can add a token to the device between the revocation and
        // the deletion.
        lockUser(userId)
        val tokens = IdentityApiTokens
        tokens.update({ (tokens.deviceId eq id) and tokens.revokedAt.isNull() }) { it[revokedAt] = at }
        t.deleteWhere { (t.id eq id) and (t.userId eq userId) } == 1
    }
}

/** Reads a device row. */
internal fun ResultRow.toDevice(): RegisteredDevice {
    val t = IdentityDevices
    return RegisteredDevice(
        id = this[t.id],
        userId = this[t.userId],
        installationId = this[t.installationId],
        name = this[t.name],
        platform = this[t.platform],
        osVersion = this[t.osVersion],
        createdAt = this[t.createdAt],
        lastLoginAt = this[t.lastLoginAt],
    )
}
