// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.kernel.database.databaseNow
import org.jetbrains.exposed.v1.core.Expression
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
 * member's) spaces admissions by its `minInterval` since the scope's last admission. Null when it may go: no current
 * advice with an interval, observation mode, or no admission yet.
 */
internal fun JdbcTransaction.pacingWait(request: AdmissionRequest): Duration? {
    val d = GatewayDirectives
    val now = databaseNow()
    val row = pacingRead(request, now).singleOrNull() ?: return null
    val last = row[d.lastAdmittedAt]
    if (last == null || readPacingSettings(request.orgId).observationOnly) return null
    val wait = last + row[d.minIntervalMs].milliseconds - row[now]
    return wait.takeIf { it > Duration.ZERO }
}

/**
 * The current advice of [request]'s scope that spaces admissions, with the database's clock: one row by the primary key
 * (`org_id`, `member_id`, `scope_key`), or none.
 *
 * @param now The clock's expression, selected as is so that the caller reads it back from the row.
 */
internal fun pacingRead(request: AdmissionRequest, now: Expression<Instant> = databaseNow()): Query {
    val d = GatewayDirectives
    return d.select(d.minIntervalMs, d.lastAdmittedAt, now).where {
        (d.orgId eq request.orgId) and (d.memberId eq request.memberId) and (d.scopeKey eq scopeOf(request.session)) and
            (d.expiresAt greater databaseNow()) and (d.minIntervalMs greater 0L)
    }
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
            (d.scopeKey inList listOf("", scopeOf(request.session)).distinct())
    }) { it[lastAdmittedAt] = databaseNow() }
}
