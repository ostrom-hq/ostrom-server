// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.infrastructure

import run.ostrom.server.governance.domain.Confirmation
import run.ostrom.server.governance.domain.ConfirmationAudit
import run.ostrom.server.governance.domain.ConfirmationStep
import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditLog
import kotlin.uuid.Uuid

/**
 * [ConfirmationAudit] on the signed audit log: each step is an event of the organization's chain, by the person who
 * made it (none for an expiry), naming the confirmation, its write, the request and the token that asked.
 *
 * @param audit The audit log.
 */
class AuditLogConfirmationAudit(private val audit: AuditLog) : ConfirmationAudit {
    override suspend fun record(confirmation: Confirmation, step: ConfirmationStep, actorUserId: String?) {
        val details = buildMap {
            put("kind", confirmation.write.kind)
            put("scope", confirmation.scope)
            put("request", "${confirmation.method} ${confirmation.path}")
            put("requestedBy", confirmation.requestedBy.userId)
            put("credential", confirmation.credential.wire)
            confirmation.tokenId?.let { put("token", it) }
            confirmation.tokenName?.let { put("tokenName", it) }
            confirmation.error?.let { put("error", it.type) }
        }
        val actor = actorUserId?.let(Uuid::parseOrNull)
        audit.record(AuditEntry(confirmation.orgId, actor, step.action, "confirmation", confirmation.id, details))
    }
}
