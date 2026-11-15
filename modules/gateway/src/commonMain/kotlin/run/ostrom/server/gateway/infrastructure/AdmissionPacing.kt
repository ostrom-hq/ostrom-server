// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.kernel.database.databaseNow
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

// The capacity controller's spacing of admissions (TeamCode's `pacing_delay` in `SqlAdmissionStore`), read and written
// in the admission's transaction, under the org's admission lock. One function per statement, so that
// PacingQueryPlansTest explains exactly what the queue sends on every call.

/**
 * How long [request] must still wait before it may be admitted: the advice of its scope (its session's, else its
 * member's when the session has none yet) spaces admissions by its `minInterval` since the scope's last admission.
 * Null when it may go: no current advice with an interval, observation mode, no admission yet, or the last admission
 * was this call's own (it went back to wait as the accounts were busy, or is retried on another account).
 */
internal fun JdbcTransaction.pacingWait(request: AdmissionRequest): Duration? {
    val d = GatewayDirectives
    val now = databaseNow()
    val row = pacingRead(request, now).singleOrNull() ?: return null
    val last = row[d.lastAdmittedAt]
    val interval = row[d.minIntervalMs]
    if (interval <= 0L || last == null || row[d.lastAdmittedRequest] == request.id || readPacingSettings(request.orgId).observationOnly) return null
    val wait = last + interval.milliseconds - row[now]
    return wait.takeIf { it > Duration.ZERO }
}

/**
 * The current advice of [request]'s scope, with the database's clock: its session's row, else its member's ('' scope)
 * when the session has none, by the primary key (`org_id`, `member_id`, `scope_key`), or none.
 *
 * @param now The clock's expression, selected as is so that the caller reads it back from the row.
 */
internal fun pacingRead(request: AdmissionRequest, now: Expression<Instant> = databaseNow()): Query {
    val d = GatewayDirectives
    return d.select(d.minIntervalMs, d.lastAdmittedAt, d.lastAdmittedRequest, now).where {
        (d.orgId eq request.orgId) and (d.memberId eq request.memberId) and (d.scopeKey inList scopesOf(request)) and
            (d.expiresAt greater databaseNow())
    }.orderBy(d.scopeKey, SortOrder.DESC).limit(1)
}

/** Records [request]'s admission on its member's advice and its session's, for the next spacing. */
internal fun JdbcTransaction.pacingAdmitted(request: AdmissionRequest) {
    write { pacingAdmittedUpdate(request) }
}

/** The admission's time on the rows of its member ('' scope) and its session, when they exist: by the primary key. */
internal fun StatementBuilder.pacingAdmittedUpdate(request: AdmissionRequest): UpdateStatement {
    val d = GatewayDirectives
    return d.update({
        (d.orgId eq request.orgId) and (d.memberId eq request.memberId) and
            (d.scopeKey inList scopesOf(request))
    }) {
        it[lastAdmittedAt] = databaseNow()
        it[lastAdmittedRequest] = request.id
    }
}

/** The scopes a call is paced in: its member's ('') and its session's, if it has one (a digest sorts after ''). */
private fun scopesOf(request: AdmissionRequest): List<String> = listOf("", scopeOf(request.session)).distinct()
