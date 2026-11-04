// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditEvent
import dev.ostrom.server.identity.domain.AuditRepository
import dev.ostrom.server.identity.domain.Mail
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.identity.domain.OrgAuditQuery
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.auditCanonicalForm
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.rules.fakes.RulesCallers
import dev.ostrom.server.rules.fakes.RulesCallers.PERSON_A
import dev.ostrom.server.rules.fakes.call
import dev.ostrom.server.rules.presentation.BulkRulesRequest
import dev.ostrom.server.rules.presentation.NewRuleRequest
import dev.ostrom.server.rules.presentation.RuleDto
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.dsl.module
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The rules write to the organization's signed audit log on a real PostgreSQL: each change to a rule and each start
 * of an analysis leaves a line in the person's name, chained and signed by the instance key, and an organization never
 * reads another's lines.
 */
class RulesAuditPostgresTest {
    private val modules =
        listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, IdentityModule, RulesModule)
    private val overrides = module {
        includes(RulesCallers.overrides(), RulesCallers.gateSessions)
        single<MailSender> {
            object : MailSender {
                override suspend fun send(mail: Mail) = Unit
            }
        }
    }

    private fun HttpResponse.expect(status: HttpStatusCode): HttpResponse = also {
        assertEquals(status, it.status, "${it.call.request.url}")
    }

    private suspend fun TestServer.lines(orgId: Uuid): List<AuditEvent> =
        koin.get<AuditRepository>().listOrg(orgId, OrgAuditQuery()).map { it.event }.reversed()

    @Test
    fun everyRuleChangeAndAnalysisLeavesASignedLineInItsAuthorsNameInItsOwnOrganization() =
        serverTest(modules, overrides) { server ->
            val created = server.call("POST", "/api/v1/rules", PERSON_A, NewRuleRequest("Ship on Fridays", "Shipping"))
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val id = created.body<RuleDto>().id
            val rule = "/api/v1/rules/$id"
            server.call("PATCH", rule, PERSON_A, buildJsonObject { put("status", "rejected") })
                .expect(HttpStatusCode.OK)
            server.call("PATCH", rule, PERSON_A, buildJsonObject { put("statement", "Ship on Thursdays") })
                .expect(HttpStatusCode.OK)
            server.call("POST", "/api/v1/rules/bulk", PERSON_A, BulkRulesRequest(listOf(id), "accepted"))
                .expect(HttpStatusCode.OK)
            server.call("DELETE", rule, PERSON_A).expect(HttpStatusCode.NoContent)
            server.call("POST", "/api/v1/analysis/start", PERSON_A).expect(HttpStatusCode.Accepted)
            server.call("POST", "/api/v1/rules", "owner-b", NewRuleRequest("Two reviewers", "Reviews"))
                .expect(HttpStatusCode.Created)

            val mine = server.lines(RulesCallers.orgA)
            val rules = listOf("created", "rejected", "edited", "accepted", "deleted").map { "rule.$it" }
            assertEquals(rules + "rule.analysis.started", mine.map { it.entry.action })
            assertTrue(mine.all { it.entry.actorId == Uuid.parse(PERSON_A) }, "In the person's name")
            assertTrue(mine.all { it.entry.details["credential"] == "browser" })
            assertEquals(List(rules.size) { "rule:$id" }, mine.take(rules.size).map { it.entry.subject() })
            assertEquals("analysis", mine.last().entry.targetType)
            assertEquals("analysis", mine.last().entry.details["kind"])
            val signer = server.koin.get<InstanceSigner>()
            val hasher = server.koin.get<SecretHasher>()
            mine.zipWithNext().forEach { (before, after) -> assertEquals(before.hash, after.previousHash) }
            for (line in mine) {
                val canonical =
                    auditCanonicalForm(line.id, line.sequence, line.entry, line.occurredAt, line.previousHash)
                assertEquals(hasher.hash(canonical), line.hash)
                assertEquals(signer.keyId, line.keyId)
                assertTrue(signer.verify(canonical.encodeToByteArray(), Base64.decode(line.signature)))
            }

            // Org B reads its own line only, never one of A's, even its rule's id.
            val theirs = server.lines(RulesCallers.orgB)
            assertEquals(listOf("rule.created"), theirs.map { it.entry.action })
            assertTrue(theirs.none { it.entry.targetId == id || it.entry.orgId == RulesCallers.orgA })
        }

    private fun AuditEntry.subject() = "$targetType:$targetId"
}
