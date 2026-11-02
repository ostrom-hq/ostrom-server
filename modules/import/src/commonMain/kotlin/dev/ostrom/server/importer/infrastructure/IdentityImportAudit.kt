// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.infrastructure

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.importer.domain.ImportAudit
import dev.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/**
 * [ImportAudit] on the identity module's signed audit log, in the organization's chain: the actor is the user who
 * runs the cutover, the details name the run's mode, its member and how many accounts it imports.
 *
 * @param log The audit log.
 */
class IdentityImportAudit(private val log: AuditLog) : ImportAudit {
    override suspend fun cutover(caller: Caller, user: Uuid, accounts: Int) {
        log.record(
            AuditEntry(
                orgId = caller.orgId,
                actorId = user,
                action = CUTOVER,
                targetType = "org",
                targetId = "${caller.orgId}",
                details = mapOf("mode" to "cutover", "memberId" to "${caller.memberId}", "accounts" to "$accounts"),
            ),
        )
    }

    /** The action names. */
    companion object {
        /** The cutover import run. */
        const val CUTOVER: String = "import.teamcode.cutover"
    }
}
