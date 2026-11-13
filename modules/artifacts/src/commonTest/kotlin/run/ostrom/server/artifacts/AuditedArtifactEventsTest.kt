// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.fakes.alice
import run.ostrom.server.artifacts.infrastructure.AuditedArtifactEvents
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class AuditedArtifactEventsTest {
    @Test
    fun anEventIsALineOfTheOrganizationsAuditLogAboutItsArtifact() = runTest {
        val lines = mutableListOf<OrgAuditRecord>()
        val events = AuditedArtifactEvents(OrgAuditTrail { lines += it })
        val principal = AuthenticatedPrincipal("alice", PrincipalKind.USER, setOf("user:*"), "alice-session")
        val id = Uuid.random()

        events.record(alice, "created", id, mapOf("access" to "team"))
        events.record(alice.copy(principal = principal), "created", id, mapOf("access" to "team", "title" to null))
        events.record(alice.copy(principal = principal), "publish_refused", null, mapOf("reason" to "secret"))

        fun line(event: String, subject: String, details: Map<String, String>) =
            OrgAuditRecord(alice.orgId, principal, "artifact.$event", "artifact", subject, details)
        assertEquals(
            listOf(
                line("created", id.toString(), mapOf("access" to "team")),
                line("publish_refused", "-", mapOf("reason" to "secret")),
            ),
            lines,
            "A caller without a credential writes nothing; a refusal before any artifact names none",
        )
    }
}
