// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.present
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.database.write
import run.ostrom.server.kernel.database.written
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushDevicesRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.uuid.Uuid

/**
 * [PushDevicesRepository] on PostgreSQL through Exposed (the table of [NotificationsMigrations]), every statement in a
 * transaction scoped to one organization by `ostrom.org_id` (`withOrganization`), which row level security enforces,
 * with its own `org_id` filter.
 *
 * @param database The serving role's pool.
 */
class PushDevicesRepositoryImpl(private val database: JdbcDatabase) : PushDevicesRepository {
    override suspend fun save(device: PushDevice, keep: Int): PushDevice = database.withOrganization(device.orgId) {
        val d = NotificationsPushDevices
        val credential = device.credential
        // A token registered again (a new language, a reinstall, another member signing in on the phone) keeps its
        // row, its id and its creation date.
        val saved = d.upsertReturning(
            d.orgId, d.pushToken,
            onUpdate = {
                it[d.memberId] = insertValue(d.memberId)
                it[d.provider] = insertValue(d.provider)
                it[d.platform] = insertValue(d.platform)
                it[d.locale] = insertValue(d.locale)
                it[d.seenAt] = insertValue(d.seenAt)
                it[d.publicKey] = insertValue(d.publicKey)
                // A registration without a credential (an older app, another kind of caller) keeps the one the phone
                // had.
                it[d.userId] = coalesce(insertValue(d.userId), d.userId)
                it[d.credentialId] = coalesce(insertValue(d.credentialId), d.credentialId)
                it[d.browser] = case()
                    .When(insertValue(d.credentialId).isNull(), d.browser)
                    .Else(insertValue(d.browser))
            },
        ) {
            it[d.orgId] = device.orgId
            it[d.id] = device.id
            it[d.memberId] = device.memberId
            it[d.pushToken] = device.pushToken
            it[d.provider] = device.provider
            it[d.platform] = device.platform
            it[d.locale] = device.locale
            it[d.createdAt] = device.createdAt
            it[d.seenAt] = device.seenAt
            it[d.userId] = credential?.userId
            it[d.credentialId] = credential?.credentialId
            it[d.browser] = credential?.browser == true
            it[d.publicKey] = device.publicKey
        }.single().toDevice()
        write { keepLatest(device.orgId, device.memberId, keep) }
        saved
    }

    override suspend fun delete(orgId: Uuid, memberId: Uuid, id: Uuid): Boolean = database.withOrganization(orgId) {
        written { deleteDevice(orgId, memberId, id) } > 0
    }

    override suspend fun ofMember(orgId: Uuid, memberId: Uuid): List<PushDevice> =
        database.withOrganization(orgId) { devicesOfMember(orgId, memberId).map { it.toDevice() } }

    override suspend fun forget(orgId: Uuid, userId: Uuid, credentialId: Uuid?) {
        database.withOrganization(orgId) { write { forgetCredentialDevices(orgId, userId, credentialId) } }
    }

    override suspend fun forgetToken(orgId: Uuid, id: Uuid, pushToken: String) {
        database.withOrganization(orgId) { write { forgetTokenDevice(orgId, id, pushToken) } }
    }

    override suspend fun deleteAll(orgId: Uuid) {
        database.withOrganization(orgId) {
            NotificationsPushDevices.deleteWhere { NotificationsPushDevices.orgId eq orgId }
        }
    }
}

/** Keeps a member's [keep] most recently seen phones of the organization. */
internal fun StatementBuilder.keepLatest(orgId: Uuid, memberId: Uuid, keep: Int): DeleteStatement {
    val d = NotificationsPushDevices
    val kept = d.select(d.id)
        .where { (d.orgId eq orgId) and (d.memberId eq memberId) }
        .orderBy(d.seenAt to SortOrder.DESC, d.id to SortOrder.DESC)
        .limit(keep)
    return d.deleteWhere { (d.orgId eq orgId) and (d.memberId eq memberId) and (d.id notInSubQuery kept) }
}

/** Removes one of a member's phones. */
internal fun StatementBuilder.deleteDevice(orgId: Uuid, memberId: Uuid, id: Uuid): DeleteStatement {
    val d = NotificationsPushDevices
    return d.deleteWhere { (d.orgId eq orgId) and (d.memberId eq memberId) and (d.id eq id) }
}

/** A member's phones, the most recently seen first. */
internal fun devicesOfMember(orgId: Uuid, memberId: Uuid): Query {
    val d = NotificationsPushDevices
    return d.select(d.columns)
        .where { (d.orgId eq orgId) and (d.memberId eq memberId) }
        .orderBy(d.seenAt to SortOrder.DESC, d.id to SortOrder.DESC)
}

/**
 * The phones a person registered with one credential ([credentialId]), or with a browser session when it is null.
 */
internal fun StatementBuilder.forgetCredentialDevices(orgId: Uuid, userId: Uuid, credentialId: Uuid?): DeleteStatement {
    val d = NotificationsPushDevices
    return d.deleteWhere {
        val ofCredential = if (credentialId == null) d.browser eq true else d.credentialId eq credentialId
        (d.orgId eq orgId) and (d.userId eq userId) and ofCredential
    }
}

/** A phone whose token the provider no longer knows, while it still has it. */
internal fun StatementBuilder.forgetTokenDevice(orgId: Uuid, id: Uuid, pushToken: String): DeleteStatement {
    val d = NotificationsPushDevices
    return d.deleteWhere { (d.orgId eq orgId) and (d.id eq id) and (d.pushToken eq pushToken) }
}

private fun ResultRow.toDevice(): PushDevice {
    val d = NotificationsPushDevices
    val credentialId = this[d.credentialId]
    return PushDevice(
        id = this[d.id],
        orgId = this[d.orgId],
        memberId = this[d.memberId],
        pushToken = this[d.pushToken],
        provider = this[d.provider],
        platform = this[d.platform],
        locale = this[d.locale],
        createdAt = this[d.createdAt],
        seenAt = this[d.seenAt],
        credential = if (credentialId == null) {
            null
        } else {
            PushCredential(present(d.userId, d.credentialId), credentialId, this[d.browser])
        },
        publicKey = this[d.publicKey],
    )
}
