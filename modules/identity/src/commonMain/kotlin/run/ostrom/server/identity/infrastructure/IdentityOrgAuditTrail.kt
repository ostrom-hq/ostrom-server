// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.TransactionalOrgAuditTrail
import run.ostrom.server.kernel.auth.PrincipalKind
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.uuid.Uuid

/**
 * [OrgAuditTrail] on the signed audit log, in the organization's chain. The actor is the person; the credential they
 * used is in the details (`credential`: `browser`, `api_token` or `mobile_app`), so a change made with an elevated
 * token is told from one made in the console. A credential that is not a person's (an agent, a service) has no actor:
 * its kind and id are in the details.
 *
 * @param log The audit log.
 */
class IdentityOrgAuditTrail(private val log: AuditLog) : OrgAuditTrail {
    override suspend fun record(record: OrgAuditRecord) {
        log.record(record.toAuditEntry())
    }
}

/**
 * [TransactionalOrgAuditTrail] on the signed audit log: the entry of [IdentityOrgAuditTrail], appended inside the
 * transaction of the change it tells.
 *
 * @param log Appends inside a sqlx4k transaction.
 */
class IdentityTransactionalOrgAuditTrail(private val log: TransactionalAuditLog) : TransactionalOrgAuditTrail {
    override suspend fun record(transaction: Transaction, record: OrgAuditRecord) {
        log.record(transaction, record.toAuditEntry())
    }
}

/** The audit entry of a change: the person as the actor, the credential in the details. */
private fun OrgAuditRecord.toAuditEntry(): AuditEntry {
    val person = Uuid.parseOrNull(actor.subjectId)?.takeIf { actor.kind == PrincipalKind.USER }
    val credential = buildMap {
        put("credential", actor.channel.name.lowercase())
        if (person == null) {
            put("actor_kind", actor.kind.name.lowercase())
            put("actor_id", actor.subjectId)
        }
    }
    return AuditEntry(orgId, person, event, subjectType, subjectId, details + credential)
}
