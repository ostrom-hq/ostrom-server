// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.DeviceAuthorization
import run.ostrom.server.identity.domain.DeviceAuthorizationStatus
import run.ostrom.server.identity.domain.DeviceAuthorizationsRepository
import run.ostrom.server.identity.domain.DeviceLimitException
import run.ostrom.server.identity.domain.DeviceSignIn
import run.ostrom.server.identity.domain.DeviceSignInRequest
import run.ostrom.server.identity.domain.MAX_REGISTERED_DEVICES
import run.ostrom.server.identity.domain.RegisteredDevice
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [DeviceAuthorizationsRepository] on PostgreSQL through Exposed ([IdentityDeviceAuthorizations]); the authorization
 * belongs to no organization, the token it gives may ([consume]).
 *
 * @param database The JDBC pool.
 */
class DeviceAuthorizationsRepositoryImpl(private val database: JdbcDatabase) : DeviceAuthorizationsRepository {
    private val t = IdentityDeviceAuthorizations

    override suspend fun create(authorization: DeviceAuthorization, deviceCodeHash: String) {
        database.query {
            t.insert {
                it[id] = authorization.id
                it[this.deviceCodeHash] = deviceCodeHash
                it[userCode] = authorization.userCode
                it[clientName] = authorization.clientName
                it[status] = authorization.status.name
                it[createdAt] = authorization.createdAt
                it[expiresAt] = authorization.expiresAt
                it[scopes] = authorization.scopes.sorted().joinToString(" ")
                it[requestedFrom] = authorization.requestedFrom
                it[installationId] = authorization.installationId
                it[deviceName] = authorization.deviceName
                it[platform] = authorization.platform
                it[osVersion] = authorization.osVersion
            }
        }
    }

    override suspend fun findByDeviceCodeHash(deviceCodeHash: String): DeviceAuthorization? = database.query {
        t.select(columns).where { t.deviceCodeHash eq deviceCodeHash }.singleOrNull()?.toAuthorization()
    }

    // The unique index identity_device_pending_code holds one pending authorization per code.
    override suspend fun findPendingByUserCode(userCode: String, now: Instant): DeviceAuthorization? = database.query {
        t.select(columns)
            .where { (t.userCode eq userCode) and (t.status eq PENDING) and (t.expiresAt greater now) }
            .singleOrNull()
            ?.toAuthorization()
    }

    override suspend fun answer(
        id: Uuid,
        userId: Uuid,
        approved: Boolean,
        now: Instant,
        approvingOrgId: Uuid?,
    ): Boolean {
        val answer = if (approved) DeviceAuthorizationStatus.APPROVED else DeviceAuthorizationStatus.DENIED
        return database.query {
            t.update({ (t.id eq id) and (t.status eq PENDING) and (t.expiresAt greater now) }) {
                it[status] = answer.name
                it[this.userId] = userId
                it[this.approvingOrgId] = approvingOrgId
            } == 1
        }
    }

    override suspend fun markPolled(id: Uuid, at: Instant) {
        database.query { t.update({ t.id eq id }) { it[lastPolledAt] = at } }
    }

    override suspend fun consume(id: Uuid, token: ApiToken, secretHash: String): Boolean {
        val orgId = token.orgId
        return if (orgId == null) {
            database.query { consumeIn(id, token, secretHash) }
        } else {
            // An organization's token is written in it (RLS): the authorization itself belongs to no organization.
            database.withOrganization(orgId) { consumeIn(id, token, secretHash) }
        }
    }

    // One update marks it consumed only if it was approved: two concurrent polls never both get a token.
    private fun JdbcTransaction.consumeIn(id: Uuid, token: ApiToken, secretHash: String): Boolean {
        val consumed = t.update({ (t.id eq id) and (t.status eq DeviceAuthorizationStatus.APPROVED.name) }) {
            it[status] = DeviceAuthorizationStatus.CONSUMED.name
        } == 1
        if (consumed) insertToken(token, secretHash)
        return consumed
    }

    // The sign-in of an installation: the person's row is locked, so that their sign-ins and removals of a device run
    // one after the other, as TeamCode's registration serializes on the member row; the limit is counted under the lock.
    override suspend fun consumeOnDevice(
        id: Uuid,
        token: ApiToken,
        secretHash: String,
        login: DeviceSignInRequest,
        now: Instant,
    ): DeviceSignIn? = database.query {
        lockUser(token.userId)
        val consumed = t.update({ (t.id eq id) and (t.status eq DeviceAuthorizationStatus.APPROVED.name) }) {
            it[status] = DeviceAuthorizationStatus.CONSUMED.name
        } == 1
        if (!consumed) return@query null
        val signIn = signIn(token.userId, login, now)
        insertToken(token.copy(deviceId = signIn.device.id), secretHash)
        signIn
    }

    private fun JdbcTransaction.signIn(userId: Uuid, login: DeviceSignInRequest, now: Instant): DeviceSignIn {
        val d = IdentityDevices
        val existing = d.select(d.columns)
            .where { (d.userId eq userId) and (d.installationId eq login.installationId) }
            .singleOrNull()?.toDevice()
        if (existing != null) {
            // A client that reports nothing keeps what a newer one reported.
            d.update({ d.id eq existing.id }) {
                it[lastLoginAt] = now
                if (login.platform != null) {
                    it[platform] = login.platform
                    it[osVersion] = login.osVersion
                }
            }
            val reported = login.platform != null
            val device = existing.copy(
                lastLoginAt = now,
                platform = if (reported) login.platform else existing.platform,
                osVersion = if (reported) login.osVersion else existing.osVersion,
            )
            return DeviceSignIn(device, registered = false)
        }
        if (d.select(d.id).where { d.userId eq userId }.limit(MAX_REGISTERED_DEVICES).count() >= MAX_REGISTERED_DEVICES) {
            throw DeviceLimitException()
        }
        val device = RegisteredDevice(
            Uuid.random(), userId, login.installationId, login.name, login.platform, login.osVersion, now, now,
        )
        d.insert {
            it[id] = device.id
            it[this.userId] = userId
            it[installationId] = device.installationId
            it[name] = device.name
            it[platform] = device.platform
            it[osVersion] = device.osVersion
            it[createdAt] = now
            it[lastLoginAt] = now
        }
        return DeviceSignIn(device, registered = true)
    }

    private val columns = listOf(
        t.id, t.userCode, t.clientName, t.status, t.userId, t.createdAt, t.expiresAt, t.lastPolledAt, t.scopes,
        t.approvingOrgId, t.requestedFrom, t.installationId, t.deviceName, t.platform, t.osVersion,
    )

    private fun ResultRow.toAuthorization() = DeviceAuthorization(
        id = this[t.id],
        userCode = this[t.userCode],
        clientName = this[t.clientName],
        status = DeviceAuthorizationStatus.valueOf(this[t.status]),
        userId = this[t.userId],
        createdAt = this[t.createdAt],
        expiresAt = this[t.expiresAt],
        lastPolledAt = this[t.lastPolledAt],
        scopes = this[t.scopes].split(' ').filter { it.isNotEmpty() }.toSet(),
        approvingOrgId = this[t.approvingOrgId],
        requestedFrom = this[t.requestedFrom],
        installationId = this[t.installationId],
        deviceName = this[t.deviceName],
        platform = this[t.platform],
        osVersion = this[t.osVersion],
    )
}

/** Locks a person's row until the end of the transaction: what their devices' writes serialize on. */
internal fun JdbcTransaction.lockUser(userId: Uuid) {
    IdentityUsers.select(IdentityUsers.id).where { IdentityUsers.id eq userId }.forUpdate().singleOrNull()
}

private val PENDING = DeviceAuthorizationStatus.PENDING.name
