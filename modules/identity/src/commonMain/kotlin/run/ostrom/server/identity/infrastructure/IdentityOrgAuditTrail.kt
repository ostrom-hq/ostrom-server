// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.PrincipalKind
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
        val actor = record.actor
        val person = Uuid.parseOrNull(actor.subjectId)?.takeIf { actor.kind == PrincipalKind.USER }
        val credential = buildMap {
            put("credential", actor.channel.name.lowercase())
            if (person == null) {
                put("actor_kind", actor.kind.name.lowercase())
                put("actor_id", actor.subjectId)
            }
        }
        val details = record.details + credential
        log.record(AuditEntry(record.orgId, person, record.event, record.subjectType, record.subjectId, details))
    }
}
