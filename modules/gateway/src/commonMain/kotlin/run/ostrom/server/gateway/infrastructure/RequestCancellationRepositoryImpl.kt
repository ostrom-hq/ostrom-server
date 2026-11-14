// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.CancellationLimitException
import run.ostrom.server.gateway.domain.RequestCancellationRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [RequestCancellationRepository] on PostgreSQL through Exposed ([GatewayCancellations]), confined to the org
 * ([JdbcDatabase.inOrg]). A marker is written under an advisory lock of the org, so that two replicas never pass the
 * bounds together; a read is one lookup by primary key.
 *
 * @param database The JDBC pool.
 */
class RequestCancellationRepositoryImpl(private val database: JdbcDatabase) : RequestCancellationRepository {
    override suspend fun cancel(orgId: Uuid, memberId: Uuid, requestId: Uuid) = database.inOrg(orgId) {
        advisoryLock(cancellationLockKey(orgId)).toList()
        write { cancellationPurge(orgId) }
        val markers = cancellationCounts(orgId, memberId, requestId).single()
        // Idempotent: a marker already there is neither extended nor counted again.
        if (markers.present == 0L) {
            if (markers.member >= RequestCancellationRepository.MEMBER_LIMIT ||
                markers.org >= RequestCancellationRepository.ORG_LIMIT
            ) {
                throw CancellationLimitException()
            }
            write { cancellationInsert(orgId, memberId, requestId) }
        }
    }

    override suspend fun isCancelled(orgId: Uuid, memberId: Uuid, requestId: Uuid): Boolean = database.inOrg(orgId) {
        !cancellationRead(orgId, memberId, requestId).empty()
    }
}

/** Whether the marker is there, and the member's and the org's markers. */
internal data class Markers(val present: Long, val member: Long, val org: Long)

private val RETENTION_SECONDS = RequestCancellationRepository.RETENTION.inWholeSeconds

/** The advisory lock key of an org's markers: counted and written one at a time, on every replica. */
internal fun cancellationLockKey(orgId: Uuid): String = "gateway_cancellations:$orgId"

/** The org's expired markers. */
internal fun StatementBuilder.cancellationPurge(orgId: Uuid): DeleteStatement =
    GatewayCancellations.deleteWhere { (this.orgId eq orgId) and (expiresAt lessEq databaseNow()) }

/** Whether the marker is there, and the member's and the org's markers. */
internal fun cancellationCounts(orgId: Uuid, memberId: Uuid, requestId: Uuid): Read<Markers> {
    val c = GatewayCancellations
    val present = countWhere((c.memberId eq memberId) and (c.requestId eq requestId))
    val member = countWhere(c.memberId eq memberId)
    val org = c.requestId.count()
    val query = c.select(present, member, org).where { c.orgId eq orgId }
    return Read(query) { Markers(it[present], it[member], it[org]) }
}

/** A new marker, kept for the retention. */
internal fun StatementBuilder.cancellationInsert(
    orgId: Uuid,
    memberId: Uuid,
    requestId: Uuid,
): InsertStatement<Number> =
    GatewayCancellations.insert {
        it[this.orgId] = orgId
        it[this.memberId] = memberId
        it[this.requestId] = requestId
        it[expiresAt] = databaseNowPlus(RETENTION_SECONDS)
    }

/** The member's live marker of a call. */
internal fun cancellationRead(orgId: Uuid, memberId: Uuid, requestId: Uuid): Query {
    val c = GatewayCancellations
    return c.select(c.requestId).where {
        (c.orgId eq orgId) and (c.memberId eq memberId) and (c.requestId eq requestId) and
            (c.expiresAt greater databaseNow())
    }
}
