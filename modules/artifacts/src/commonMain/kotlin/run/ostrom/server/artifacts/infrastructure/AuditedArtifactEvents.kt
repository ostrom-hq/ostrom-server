// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactEvents
import run.ostrom.server.kernel.audit.MAX_AUDIT_DETAIL
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import kotlin.uuid.Uuid

/**
 * [ArtifactEvents] written to the organization's audit log, as `artifact.<event>` about the artifact, its actor the
 * credential that made the change. A caller with no credential known (a test, a job) writes nothing.
 *
 * @param trail The organization's audit log.
 */
internal class AuditedArtifactEvents(private val trail: OrgAuditTrail) : ArtifactEvents {
    override suspend fun record(
        caller: ArtifactCaller,
        event: String,
        artifactId: Uuid?,
        details: Map<String, String?>,
    ) {
        val principal = caller.principal ?: return
        val subject = if (artifactId == null) NONE else artifactId.toString()
        val facts = details.mapNotNull { (key, value) -> value?.let { key to it.take(MAX_AUDIT_DETAIL) } }.toMap()
        trail.record(OrgAuditRecord(caller.orgId, principal, "artifact.$event", "artifact", subject, facts))
    }

    private companion object {
        /** The subject of a refusal, before any artifact exists. */
        const val NONE = "-"
    }
}
