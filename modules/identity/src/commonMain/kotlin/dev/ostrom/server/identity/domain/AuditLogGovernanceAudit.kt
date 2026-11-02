// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.GovernanceAudit
import kotlin.uuid.Uuid

/**
 * [GovernanceAudit] on the signed audit log: each governance write of the token of `ostrom login --admin` is a
 * `governance.write` event of its organization's chain, by its person, naming the request, the scope and the token.
 *
 * @param audit The audit log.
 */
class AuditLogGovernanceAudit(private val audit: AuditLog) : GovernanceAudit {
    override suspend fun record(principal: AuthenticatedPrincipal, scope: String, target: String) {
        val details = mapOf("scope" to scope, "token" to principal.credentialId.orEmpty())
        val orgId = principal.orgId?.let(Uuid::parse)
        audit.record(AuditEntry(orgId, Uuid.parse(principal.subjectId), "governance.write", "route", target, details))
    }
}
