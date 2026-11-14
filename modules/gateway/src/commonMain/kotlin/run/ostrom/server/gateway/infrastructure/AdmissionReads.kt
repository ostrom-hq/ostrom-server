// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.QueueCandidate
import run.ostrom.server.gateway.domain.QueueCounts
import run.ostrom.server.gateway.domain.Workload
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

// The reads of the admission queue (AdmissionQueueRepositoryImpl), one function each so that AdmissionQueuePostgresTest
// explains exactly what the repository sends. Every one filters on the org.

private val ALIVE_SECONDS = AdmissionQueueRepository.ALIVE.inWholeSeconds

/**
 * A read and how its rows become what the repository decides on: the [query] alone for a plan.
 *
 * @property query What is sent.
 */
internal class Read<T>(val query: Query, private val map: (ResultRow) -> T) {
    /** The rows, mapped. */
    fun rows(): List<T> = query.map(map)

    /** The one row of an aggregate. */
    fun single(): T = map(query.single())

    /** The row, if any. */
    fun singleOrNull(): T? = query.singleOrNull()?.let(map)
}

/** A member's last turn in a scope ([mine], 0 before their first) and the scope's last ([top]). */
internal data class Turns(val mine: Long, val top: Long)

/** The calls waiting in the org, and the member's. */
internal data class Waiting(val org: Long, val member: Long)

/** How long a waiting call has waited, by the database's clock; no row when its place expired. */
internal fun admissionWaited(orgId: Uuid, id: Uuid): Read<Long> {
    val a = GatewayAdmissions
    val now = databaseNow()
    val query = a.select(a.createdAt, now).where { (a.orgId eq orgId) and (a.id eq id) }
    return Read(query) { (it[now] - it[a.createdAt]).inWholeMilliseconds }
}

/** The calls a member runs now. */
internal fun admissionRunning(orgId: Uuid, memberId: Uuid): Read<Int> {
    val a = GatewayAdmissions
    val running = a.id.count()
    val query = a.select(running).where { (a.orgId eq orgId) and (a.memberId eq memberId) and (a.running eq true) }
    return Read(query) { it[running].toInt() }
}

/**
 * The other calls of the scope that wait and may run: not blocked by their member's limit, and polled lately (a call
 * whose replica stopped never holds the turn for long), oldest first, with their member's last turn.
 */
internal fun admissionCandidates(orgId: Uuid, scope: String, id: Uuid): Read<QueueCandidate> {
    val a = GatewayAdmissions
    val t = GatewayAdmissionTurns
    val now = databaseNow()
    val lastTurn = coalesce(t.turn, longLiteral(0))
    val query = a.join(
        t,
        JoinType.LEFT,
        additionalConstraint = { (t.orgId eq a.orgId) and (t.scope eq a.scope) and (t.memberId eq a.memberId) },
    )
        .select(a.id, a.memberId, a.workload, a.createdAt, now, lastTurn)
        .where {
            (a.orgId eq orgId) and (a.scope eq scope) and (a.running eq false) and (a.readyAt lessEq now) and
                (a.polledAt greater databaseNowPlus(-ALIVE_SECONDS)) and (a.id neq id)
        }
        .orderBy(a.createdAt)
        .limit(AdmissionQueueRepository.ORG_BOUND)
    return Read(query) {
        QueueCandidate(
            id = it[a.id],
            memberId = it[a.memberId],
            workload = Workload.valueOf(it[a.workload]),
            waited = (it[now] - it[a.createdAt]).inWholeMilliseconds.milliseconds,
            lastTurn = it[lastTurn],
        )
    }
}

/** The member's last turn in the scope, and the scope's last. */
internal fun admissionTurns(orgId: Uuid, scope: String, memberId: Uuid): Read<Turns> {
    val t = GatewayAdmissionTurns
    val mine = maxWhere(t.memberId eq memberId, t.turn)
    val top = maxOrZero(t.turn)
    val query = t.select(mine, top).where { (t.orgId eq orgId) and (t.scope eq scope) }
    return Read(query) { Turns(it[mine], it[top]) }
}

/** The calls waiting in the org, and the member's. */
internal fun admissionWaiting(orgId: Uuid, memberId: Uuid): Read<Waiting> {
    val a = GatewayAdmissions
    val org = a.id.count()
    val member = countWhere(a.memberId eq memberId)
    val query = a.select(org, member).where { (a.orgId eq orgId) and (a.running eq false) }
    return Read(query) { Waiting(it[org], it[member]) }
}

/** The calls waiting now, the org's and the member's. */
internal fun admissionCounts(orgId: Uuid, memberId: Uuid): Read<QueueCounts> {
    val a = GatewayAdmissions
    val waiting = a.id.count()
    val mine = countWhere(a.memberId eq memberId)
    val query = a.select(waiting, mine)
        .where { (a.orgId eq orgId) and (a.running eq false) and (a.expiresAt greater databaseNow()) }
    return Read(query) { QueueCounts(it[waiting].toInt(), it[mine].toInt()) }
}
