// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.TransactionalOrgAuditTrail
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
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
 * transaction of the change it tells. The entry is signed on the chain's head read in a first transaction and appended
 * in the second, under the chain's lock, only if the head is still the one it was signed on; the change is then rolled
 * back and signed again, [ATTEMPTS] times at most.
 *
 * @param log Reads the chain's head, signs, and appends inside an Exposed transaction.
 */
class IdentityTransactionalOrgAuditTrail(private val log: TransactionalAuditLog) : TransactionalOrgAuditTrail {
    override suspend fun <T> recording(
        database: JdbcDatabase,
        record: OrgAuditRecord,
        change: JdbcTransaction.(append: () -> Boolean) -> T,
    ): T {
        val orgId = record.orgId
        repeat(ATTEMPTS) {
            val head = database.inOrg(orgId) { log.head(this, orgId) }
            val events = log.sign(head, listOf(record.toAuditEntry()))
            var chained = true
            val result = database.inOrg(orgId) {
                change {
                    log.append(this, orgId, head, events).also { appended ->
                        if (!appended) {
                            chained = false
                            rollback()
                        }
                    }
                }
            }
            if (chained) return result
        }
        error("The organization's audit chain kept moving while the change's entry was signed.")
    }
}

// The signatures of one change on a head another write moved meanwhile, before the change fails loudly.
private const val ATTEMPTS = 8

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
