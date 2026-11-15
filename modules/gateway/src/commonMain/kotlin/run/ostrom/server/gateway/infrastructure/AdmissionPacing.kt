// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.kernel.database.databaseNow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The capacity controller's spacing of admissions (TeamCode's `pacing_delay` in `SqlAdmissionStore`), read and written
// in the admission's transaction, under the org's admission lock.

/**
 * How long [request] must still wait before it may be admitted: the advice of its scope (its session's, else its
 * member's) spaces admissions by its `minInterval` since the scope's last admission. Null when it may go: no current
 * advice with an interval, observation mode, or no admission yet.
 */
internal fun JdbcTransaction.pacingWait(request: AdmissionRequest): Duration? {
    val d = GatewayDirectives
    val row = d.select(d.minIntervalMs, d.lastAdmittedAt).where {
        (d.orgId eq request.orgId) and (d.memberId eq request.memberId) and (d.scopeKey eq scopeOf(request.session)) and
            (d.expiresAt greater databaseNow()) and (d.minIntervalMs greater 0L)
    }.singleOrNull() ?: return null
    val last = row[d.lastAdmittedAt]
    if (last == null || readPacingSettings(request.orgId).observationOnly) return null
    val now = databaseNow()
    val wait = last + row[d.minIntervalMs].milliseconds - Table.Dual.select(now).single()[now]
    return wait.takeIf { it > Duration.ZERO }
}

/** Records [request]'s admission on its member's advice and its session's, for the next spacing. */
internal fun JdbcTransaction.pacingAdmitted(request: AdmissionRequest) {
    val d = GatewayDirectives
    d.update({
        (d.orgId eq request.orgId) and (d.memberId eq request.memberId) and
            (d.scopeKey inList listOf("", scopeOf(request.session)).distinct())
    }) { it[lastAdmittedAt] = databaseNow() }
}
