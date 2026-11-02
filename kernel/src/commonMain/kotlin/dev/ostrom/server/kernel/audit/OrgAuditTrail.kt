// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.audit

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlin.uuid.Uuid

/**
 * A change written to an organization's audit log (`GET /api/v1/audit`): metadata only, never a secret's value, a
 * prompt or a script.
 *
 * @property orgId The organization.
 * @property actor Who made it: the person, and the credential they used (a browser session, an elevated token).
 * @property event What happened, a stable dotted name (`inference.policy.changed`).
 * @property subjectType What kind of thing it happened to (`policy`, `budget`, `mcp_server`).
 * @property subjectId Which one.
 * @property details Extra facts, never a secret.
 */
data class OrgAuditRecord(
    val orgId: Uuid,
    val actor: AuthenticatedPrincipal,
    val event: String,
    val subjectType: String,
    val subjectId: String,
    val details: Map<String, String> = emptyMap(),
)

/**
 * Writes the governance changes of an organization to its signed audit log, the one `GET /api/v1/audit` reads. The
 * identity module binds it on that log; a module only depends on this port. Until it is bound,
 * [serverKoin][dev.ostrom.server.kernel.module.serverKoin] binds [DiscardingOrgAuditTrail], so a module tested alone
 * starts; the server always runs with the identity module.
 */
fun interface OrgAuditTrail {
    /**
     * Records a change, after it is made.
     *
     * @param record The change.
     */
    suspend fun record(record: OrgAuditRecord)
}

/** Writes nothing: the default for a server built without the identity module (a module's own tests). */
class DiscardingOrgAuditTrail : OrgAuditTrail {
    override suspend fun record(record: OrgAuditRecord) = Unit
}

/** The longest value a detail keeps: a long list is cut, the log never holds a whole document. */
const val MAX_AUDIT_DETAIL: Int = 200

/**
 * The details of an [OrgAuditRecord] from the facts of a change: an absent fact (null) is left out rather than written
 * as `null`, a list is joined with commas, and every value is cut at [MAX_AUDIT_DETAIL] characters.
 *
 * @param facts The facts, by name.
 *
 * @return The details.
 */
fun auditDetails(vararg facts: Pair<String, Any?>): Map<String, String> =
    facts.mapNotNull { (key, value) -> value?.let { key to it.auditText() } }.toMap()

private fun Any.auditText(): String =
    (if (this is Collection<*>) joinToString(",") else toString()).take(MAX_AUDIT_DETAIL)
