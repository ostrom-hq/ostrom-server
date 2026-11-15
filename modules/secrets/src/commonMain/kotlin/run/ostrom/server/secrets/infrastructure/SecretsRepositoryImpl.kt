// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.rowLess
import run.ostrom.server.secrets.domain.Secret
import run.ostrom.server.secrets.domain.SecretCursor
import run.ostrom.server.secrets.domain.SecretsRepository
import run.ostrom.server.secrets.domain.StoredSecret
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SecretsRepository] on `secrets_entries` (table of [SecretsMigrations]): every statement filters on its
 * organization and runs in it (`inOrg`), under row-level security.
 *
 * @param database The serving role's pool.
 */
class SecretsRepositoryImpl(private val database: JdbcDatabase) : SecretsRepository {
    override suspend fun create(stored: StoredSecret): Boolean {
        val secret = stored.secret
        return database.inOrg(secret.orgId) {
            // ON CONFLICT DO NOTHING: a name taken (by its owner) is a refusal the use case reports, not an error.
            SecretsEntries.insertIgnore {
                it[id] = secret.id
                it[orgId] = secret.orgId
                it[name] = secret.name
                it[ownerMemberId] = secret.ownerMemberId
                it[envelope] = stored.envelope
                it[version] = secret.version
                it[createdAt] = secret.createdAt
                it[updatedAt] = secret.updatedAt
                it[updatedBy] = secret.updatedBy
            }.insertedCount == 1
        }
    }

    override suspend fun get(orgId: Uuid, id: Uuid): StoredSecret? = database.inOrg(orgId) {
        storedRead { (s.orgId eq orgId) and (s.id eq id) }.singleOrNull()?.toStored()
    }

    override suspend fun getMany(orgId: Uuid, ids: Collection<Uuid>): List<StoredSecret> {
        if (ids.isEmpty()) return emptyList()
        return database.inOrg(orgId) {
            storedRead { (s.orgId eq orgId) and (s.id inList ids) }.map { it.toStored() }
        }
    }

    override suspend fun list(orgId: Uuid, memberId: Uuid?, after: SecretCursor?, limit: Int): List<Secret> =
        database.inOrg(orgId) {
            SecretsEntries.select(COLUMNS)
                .where {
                    (s.orgId eq orgId) andIfNotNull
                        memberId?.let { (s.ownerMemberId.isNull() or (s.ownerMemberId eq it)) } andIfNotNull
                        after?.let(::afterCursor)
                }
                .orderBy(s.name to SortOrder.ASC, s.id to SortOrder.ASC)
                .limit(limit)
                .map { it.toSecret() }
        }

    override suspend fun rotate(orgId: Uuid, id: Uuid, envelope: String, updatedAt: Instant, updatedBy: Uuid): Secret? =
        database.inOrg(orgId) {
            s.updateReturning(COLUMNS, where = { (s.orgId eq orgId) and (s.id eq id) }) {
                it[this.envelope] = envelope
                it[version] = version + 1
                it[this.updatedAt] = updatedAt
                it[this.updatedBy] = updatedBy
            }.singleOrNull()?.toSecret()
        }

    override suspend fun findManyByName(orgId: Uuid, ownerMemberId: Uuid?, names: Collection<String>): List<Secret> {
        if (names.isEmpty()) return emptyList()
        return database.inOrg(orgId) {
            val owner = if (ownerMemberId == null) s.ownerMemberId.isNull() else s.ownerMemberId eq ownerMemberId
            SecretsEntries.select(COLUMNS)
                .where { (s.orgId eq orgId) and (s.name inList names) and owner }
                .map { it.toSecret() }
        }
    }

    override suspend fun getOrgSecretsByName(orgId: Uuid, names: Collection<String>): List<StoredSecret> {
        if (names.isEmpty()) return emptyList()
        return database.inOrg(orgId) {
            storedRead { (s.orgId eq orgId) and s.ownerMemberId.isNull() and (s.name inList names) }
                .map { it.toStored() }
        }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = database.inOrg(orgId) {
        s.deleteWhere { (s.orgId eq orgId) and (s.id eq id) } == 1
    }
}

private val s = SecretsEntries

/** Every column but the envelope, which only the reads that reveal or check a value take. */
private val COLUMNS = listOf(s.id, s.orgId, s.name, s.ownerMemberId, s.version, s.createdAt, s.updatedAt, s.updatedBy)

// `(cursor) < (name, id)`, that is `(name, id) > (cursor)`: the page after the cursor's secret, in the list's order.
private fun afterCursor(cursor: SecretCursor): Op<Boolean> = rowLess(
    listOf(QueryParameter(cursor.name, TextColumnType()), QueryParameter(cursor.id, UuidColumnType())),
    listOf(s.name, s.id),
)

private fun storedRead(where: () -> Op<Boolean>) = SecretsEntries.select(COLUMNS + s.envelope).where(where())

private fun ResultRow.toStored() = StoredSecret(toSecret(), this[s.envelope])

private fun ResultRow.toSecret() = Secret(
    id = this[s.id],
    orgId = this[s.orgId],
    name = this[s.name],
    ownerMemberId = this[s.ownerMemberId],
    version = this[s.version],
    createdAt = this[s.createdAt],
    updatedAt = this[s.updatedAt],
    updatedBy = this[s.updatedBy],
)
