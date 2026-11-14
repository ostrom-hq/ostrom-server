// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import kotlin.uuid.Uuid

// The writes of a running call's lease (AdmissionQueueRepositoryImpl), one function each so that
// AdmissionQueuePostgresTest explains exactly what the repository sends. Every one filters on the org.

/**
 * The turn a running call took, given back to its member as it goes back to wait (it was not served), unless another
 * of their calls took a later one since: `UPDATE gateway_admission_turns SET turn = turn_before FROM
 * gateway_admissions WHERE …`.
 */
internal fun StatementBuilder.admissionGiveBack(orgId: Uuid, id: Uuid): UpdateStatement {
    val a = GatewayAdmissions
    val t = GatewayAdmissionTurns
    val taken = t.join(
        a,
        JoinType.INNER,
        additionalConstraint = {
            (a.orgId eq t.orgId) and (a.scope eq t.scope) and (a.memberId eq t.memberId) and (t.turn eq a.turnTaken)
        },
    )
    // turn_before is set with turn_taken, which the join requires.
    return taken.update({ (a.orgId eq orgId) and (a.id eq id) and (a.running eq true) and (t.orgId eq orgId) }) {
        it[t.turn] = a.turnBefore
    }
}

/** A running call back to wait, at its place. */
internal fun StatementBuilder.admissionRequeue(orgId: Uuid, id: Uuid): UpdateStatement =
    GatewayAdmissions.update({ (GatewayAdmissions.orgId eq orgId) and (GatewayAdmissions.id eq id) }) {
        it[running] = false
        it[turnTaken] = null
        it[turnBefore] = null
        it[readyAt] = databaseNow()
        it[polledAt] = databaseNow()
        it[expiresAt] = databaseNowPlus(WAITING_SECONDS)
    }

/** A running call's lease extended. */
internal fun StatementBuilder.admissionRenew(orgId: Uuid, id: Uuid): UpdateStatement =
    GatewayAdmissions.update({
        (GatewayAdmissions.orgId eq orgId) and (GatewayAdmissions.id eq id) and (GatewayAdmissions.running eq true)
    }) {
        it[expiresAt] = databaseNowPlus(RUNNING_SECONDS)
    }

/** A call that ended or stopped waiting. */
internal fun StatementBuilder.admissionRelease(orgId: Uuid, id: Uuid): DeleteStatement =
    GatewayAdmissions.deleteWhere { (this.orgId eq orgId) and (this.id eq id) }
