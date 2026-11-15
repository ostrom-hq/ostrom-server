// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.infrastructure

import run.ostrom.server.admin.domain.AdminSource
import run.ostrom.server.admin.domain.InstanceAdmin
import run.ostrom.server.admin.domain.InstanceAdminsRepository
import run.ostrom.server.admin.domain.RemoveOutcome
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [InstanceAdminsRepository] on PostgreSQL (`admin_instance_admins`, the instance's, no organization).
 *
 * @param database The serving role's pool.
 */
class InstanceAdminsRepositoryImpl(private val database: JdbcDatabase) : InstanceAdminsRepository {
    override suspend fun list(): List<InstanceAdmin> = database.query { currentAdmins().map { it.toAdmin() } }

    override suspend fun isAdmin(userId: Uuid): Boolean = database.query { !isCurrent(userId).empty() }

    override suspend fun addIfNeverSeen(admin: InstanceAdmin): Boolean = database.query {
        AdminInstanceAdmins.insertIgnore { it.values(admin) }.insertedCount == 1
    }

    override suspend fun add(admin: InstanceAdmin): Boolean = database.query {
        val a = AdminInstanceAdmins
        // A current administrator stays as added; a removed one comes back, as added now.
        a.upsert(
            a.userId,
            onUpdate = {
                it[a.addedVia] = insertValue(a.addedVia)
                it[a.addedAt] = insertValue(a.addedAt)
                it[a.addedBy] = insertValue(a.addedBy)
                it[a.removedAt] = null
                it[a.removedBy] = null
            },
            where = { a.removedAt.isNotNull() },
        ) { it.values(admin) }.insertedCount == 1
    }

    override suspend fun remove(userId: Uuid, by: Uuid, at: Instant): RemoveOutcome = database.query {
        // The current administrators locked first, in one order: two removals at once cannot leave none.
        val current = currentAdmins().forUpdate().map { it[AdminInstanceAdmins.userId] }
        when {
            isCurrent(userId).empty() -> RemoveOutcome.NOT_FOUND
            current.size == 1 -> RemoveOutcome.LAST
            else -> {
                AdminInstanceAdmins.update({ AdminInstanceAdmins.userId eq userId }) {
                    it[removedAt] = at
                    it[removedBy] = by
                }
                RemoveOutcome.REMOVED
            }
        }
    }

    private fun UpdateBuilder<*>.values(admin: InstanceAdmin) {
        this[AdminInstanceAdmins.userId] = admin.userId
        this[AdminInstanceAdmins.addedVia] = admin.source.wire
        this[AdminInstanceAdmins.addedAt] = admin.addedAt
        this[AdminInstanceAdmins.addedBy] = admin.addedBy
    }

    private fun ResultRow.toAdmin() = InstanceAdmin(
        userId = this[AdminInstanceAdmins.userId],
        source = AdminSource.of(this[AdminInstanceAdmins.addedVia]),
        addedAt = this[AdminInstanceAdmins.addedAt],
        addedBy = this[AdminInstanceAdmins.addedBy],
    )
}

/** The current administrators, oldest first, at most [MAX_LISTED_ADMINS]. */
internal fun currentAdmins(): Query {
    val a = AdminInstanceAdmins
    return a.select(a.userId, a.addedVia, a.addedAt, a.addedBy)
        .where { a.removedAt.isNull() }
        .orderBy(a.addedAt to SortOrder.ASC, a.userId to SortOrder.ASC)
        .limit(MAX_LISTED_ADMINS)
}

/** [userId]'s row if it is a current administrator: a primary-key lookup. */
internal fun isCurrent(userId: Uuid): Query {
    val a = AdminInstanceAdmins
    return a.select(a.userId).where { (a.userId eq userId) and a.removedAt.isNull() }
}

/** An instance has a handful of administrators: the bound of a list, far above any real one. */
internal const val MAX_LISTED_ADMINS = 1_000
