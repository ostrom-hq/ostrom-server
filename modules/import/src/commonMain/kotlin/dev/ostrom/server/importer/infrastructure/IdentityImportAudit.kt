// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.infrastructure

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.importer.domain.ImportAudit
import dev.ostrom.server.secrets.domain.Caller

/**
 * [ImportAudit] on the identity module's signed audit log, in the organization's chain. The caller carries a member
 * id, not a user id, so the event names the member in its details.
 *
 * @param log The audit log.
 */
class IdentityImportAudit(private val log: AuditLog) : ImportAudit {
    override suspend fun cutover(caller: Caller, accounts: Int) {
        log.record(
            AuditEntry(
                orgId = caller.orgId,
                actorId = null,
                action = CUTOVER,
                targetType = "org",
                targetId = "${caller.orgId}",
                details = mapOf("memberId" to "${caller.memberId}", "accounts" to "$accounts"),
            ),
        )
    }

    /** The action names. */
    companion object {
        /** The cutover import run. */
        const val CUTOVER: String = "import.teamcode.cutover"
    }
}
