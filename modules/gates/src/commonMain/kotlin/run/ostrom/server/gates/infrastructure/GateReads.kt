// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.kernel.database.Read
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The reads of the gates (GatesRepositoryImpl), one function each so that GatesQueryPlansTest explains exactly what the
// repository sends. Every one filters on the organization; the transaction's row-level security is the second wall.

/** The most gates a session's read returns. */
internal const val MAX_SESSION_GATES = 500

/**
 * One gate, locked for the transaction when [lock] (an answer, a reservation, a use): `FOR UPDATE`.
 *
 * @param orgId The organization.
 * @param id The gate.
 * @param lock Whether the row is locked.
 */
internal fun gateRead(orgId: Uuid, id: Uuid, lock: Boolean): Read<Gate> {
    val g = GatesGates
    val query = g.select(GATE_COLUMNS).where { (g.orgId eq orgId) and (g.id eq id) }
    return Read(if (lock) query.forUpdate() else query) { it.toGate() }
}

/** The newest gates of one action ([key]), under the action's advisory lock, at most [limit]. */
internal fun gatesByKey(orgId: Uuid, key: String, limit: Int): Read<Gate> {
    val g = GatesGates
    val query = g.select(GATE_COLUMNS).where { (g.orgId eq orgId) and (g.key eq key) }
        .orderBy(g.createdAt to SortOrder.DESC, g.id to SortOrder.DESC)
        .limit(limit)
    return Read(query) { it.toGate() }
}

/**
 * The gates opened since [since], newest first, at most [limit]: the rules' replay, read by a background worker once
 * per replay, never on the request path. The organization's prefix of `gates_gates_by_key` finds its rows.
 */
internal fun gatesRecent(orgId: Uuid, since: Instant, limit: Int): Read<Gate> {
    val g = GatesGates
    val query = g.select(GATE_COLUMNS).where { (g.orgId eq orgId) and (g.createdAt greaterEq since) }
        .orderBy(g.createdAt to SortOrder.DESC, g.id to SortOrder.DESC)
        .limit(limit)
    return Read(query) { it.toGate() }
}

/** The gates of a page of approvals, by id: at most one row each. */
internal fun gatesByIds(orgId: Uuid, ids: List<Uuid>): Read<Gate> {
    val g = GatesGates
    val query = g.select(GATE_COLUMNS).where { (g.orgId eq orgId) and (g.id inList ids) }.limit(ids.size)
    return Read(query) { it.toGate() }
}

/**
 * The gates a session asked or joined, oldest first, through their links (`gates_sessions`): under row level security
 * a qual that is not leakproof, like `jsonb @>` on `session_ids`, is never an index condition.
 */
internal fun sessionGatesRead(orgId: Uuid, sessionId: String): Read<Gate> {
    val s = GatesSessions
    val g = GatesGates
    val query = s.join(g, JoinType.INNER, s.gateId, g.id)
        .select(GATE_COLUMNS)
        .where { (s.orgId eq orgId) and (s.sessionId eq sessionId) and (g.orgId eq orgId) }
        .orderBy(g.createdAt to SortOrder.ASC, g.id to SortOrder.ASC)
        .limit(MAX_SESSION_GATES)
    return Read(query) { it.toGate() }
}

/** When a session's end read its gates, on the primary key of `gates_session_ends`. */
internal fun sessionEndRead(orgId: Uuid, sessionId: String): Read<Instant> {
    val e = GatesSessionEnds
    val query = e.select(e.endedAt).where { (e.orgId eq orgId) and (e.sessionId eq sessionId) }
    return Read(query) { it[e.endedAt] }
}
