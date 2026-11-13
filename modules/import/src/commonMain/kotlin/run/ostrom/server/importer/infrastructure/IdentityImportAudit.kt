// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.importer.domain.ImportAudit
import run.ostrom.server.secrets.domain.Caller
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
