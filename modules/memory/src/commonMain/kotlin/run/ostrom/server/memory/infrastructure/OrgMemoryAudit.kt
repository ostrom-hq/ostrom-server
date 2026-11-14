// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.memory.domain.MemoryAudit
import kotlin.uuid.Uuid

/**
 * [MemoryAudit] on the organization's signed audit log: subject `memory`, the entry's id, the credential that wrote it
 * (the log names a key's or a token's id), and the facts given; never a title or content, personal entries included.
 *
 * @param trail The organization's audit log.
 */
internal class OrgMemoryAudit(private val trail: OrgAuditTrail) : MemoryAudit {
    override suspend fun record(
        orgId: Uuid,
        actor: AuthenticatedPrincipal,
        event: String,
        id: Uuid,
        vararg facts: Pair<String, String>,
    ) {
        trail.record(OrgAuditRecord(orgId, actor, event, "memory", id.toString(), facts.toMap()))
    }
}
