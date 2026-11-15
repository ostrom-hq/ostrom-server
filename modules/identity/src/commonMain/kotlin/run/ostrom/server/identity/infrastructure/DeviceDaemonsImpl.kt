// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.kernel.auth.DaemonFolder
import run.ostrom.server.kernel.auth.DaemonListingRequest
import run.ostrom.server.kernel.auth.DaemonPresence
import run.ostrom.server.kernel.auth.DeviceDaemons
import run.ostrom.server.kernel.auth.OwnedDevice
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [DeviceDaemons] on PostgreSQL through Exposed (TeamCode's `SqlDeviceDaemonStore` but the queue): presence on the
 * device row (one primary-key update per poll, skipped within [DaemonPresence.TOUCH]), folders in
 * `identity_device_folders`, listings in `identity_device_daemon_listings` for as long as a member waits, their answers
 * sealed by the caller. Account-level tables, reached outside any organization.
 *
 * @param database The JDBC pool.
 */
class DeviceDaemonsImpl(private val database: JdbcDatabase) : DeviceDaemons {
    private val devices = IdentityDevices
    private val folders = IdentityDeviceFolders
    private val listings = IdentityDeviceDaemonListings

    override suspend fun touch(deviceId: Uuid, version: String?, features: List<String>, now: Instant) {
        database.query {
            val stale = devices.daemonSeenAt.isNull() or (devices.daemonSeenAt less now - DaemonPresence.TOUCH)
            devices.update({ (devices.id eq deviceId) and stale }) {
                it[daemonSeenAt] = now
                it[daemonVersion] = version
                it[daemonFeatures] = features.takeIf(List<String>::isNotEmpty)?.joinToString(",")
            }
        }
    }

    override suspend fun offline(deviceId: Uuid) {
        database.query { devices.update({ devices.id eq deviceId }) { it[daemonSeenAt] = null } }
    }

    override suspend fun publish(deviceId: Uuid, folders: List<DaemonFolder>) {
        database.query {
            // Serialized on the device row, so two publications never interleave their delete and insert.
            devices.select(devices.id).where { devices.id eq deviceId }.forUpdate().singleOrNull() ?: return@query
            val t = this@DeviceDaemonsImpl.folders
            t.deleteWhere { t.deviceId eq deviceId }
            t.batchInsert(folders.withIndex(), shouldReturnGeneratedValues = false) { (index, folder) ->
                this[t.deviceId] = deviceId
                this[t.position] = index
                this[t.folderId] = folder.id
                this[t.name] = folder.name
                this[t.label] = folder.label
            }
        }
    }

    override suspend fun owned(userId: Uuid, deviceId: Uuid, now: Instant): OwnedDevice? = database.query {
        // The device row's person decides, never the caller's role.
        val columns =
            listOf(devices.id, devices.name, devices.daemonSeenAt, devices.daemonVersion, devices.daemonFeatures)
        val row = devices.select(columns)
            .where { (devices.id eq deviceId) and (devices.userId eq userId) }
            .singleOrNull() ?: return@query null
        val published = folders.select(folders.folderId, folders.name, folders.label)
            .where { folders.deviceId eq deviceId }
            .orderBy(folders.position to SortOrder.ASC)
            .map { DaemonFolder(it[folders.folderId], it[folders.name], it[folders.label]) }
        val presence = DaemonPresence.of(
            row[devices.daemonSeenAt],
            row[devices.daemonVersion],
            now,
            daemonFeatures(row[devices.daemonFeatures]),
        )
        OwnedDevice(row[devices.id], row[devices.name], presence, published)
    }

    override suspend fun askListing(
        deviceId: Uuid,
        folderId: String,
        limit: Int,
        before: String?,
        now: Instant,
        expiresAt: Instant,
    ): Uuid = database.query {
        // What a stopped replica left behind for this device goes now; the device's rows are few, found by the index.
        listings.deleteWhere { (listings.deviceId eq deviceId) and (listings.expiresAt lessEq now) }
        val id = Uuid.random()
        listings.insert {
            it[this.id] = id
            it[this.deviceId] = deviceId
            it[this.folderId] = folderId
            it[maxResults] = limit
            it[this.before] = before
            it[this.expiresAt] = expiresAt
        }
        id
    }

    override suspend fun listings(deviceId: Uuid, now: Instant, limit: Int): List<DaemonListingRequest> =
        database.query {
            listings.select(listings.id, listings.folderId, listings.maxResults, listings.before)
                .where {
                    (listings.deviceId eq deviceId) and listings.answeredAt.isNull() and
                        (listings.expiresAt greater now)
                }
                .orderBy(listings.expiresAt to SortOrder.ASC)
                .limit(limit)
                .map {
                    DaemonListingRequest(
                        it[listings.id],
                        it[listings.folderId],
                        it[listings.maxResults],
                        it[listings.before],
                    )
                }
        }

    override suspend fun answerListing(deviceId: Uuid, requestId: Uuid, sealed: String, now: Instant): Boolean =
        database.query {
            val open = (listings.id eq requestId) and (listings.deviceId eq deviceId) and
                listings.answeredAt.isNull() and (listings.expiresAt greater now)
            listings.update({ open }) {
                it[answeredAt] = now
                it[answer] = sealed
            } > 0
        }

    override suspend fun listingAnswer(requestId: Uuid): String? = database.query {
        listings.select(listings.answeredAt, listings.answer)
            .where { listings.id eq requestId }
            .singleOrNull()
            ?.takeIf { it[listings.answeredAt] != null }
            ?.get(listings.answer)
    }

    override suspend fun dropListing(requestId: Uuid) {
        database.query { listings.deleteWhere { listings.id eq requestId } }
    }

}

/** A daemon's features as the device row stores them: comma-separated, null when it announced none. */
internal fun daemonFeatures(stored: String?): List<String> = stored?.split(',').orEmpty()
