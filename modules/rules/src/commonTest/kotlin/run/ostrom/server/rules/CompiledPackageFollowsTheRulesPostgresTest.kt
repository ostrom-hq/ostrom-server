// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules

import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.services.CompiledPackages
import run.ostrom.server.compiler.domain.usecases.PublishPackageUseCase
import run.ostrom.server.compiler.domain.usecases.ServedPackage
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.rules.domain.repositories.RulesRepository
import run.ostrom.server.rules.fakes.RulesCallers
import run.ostrom.server.rules.fakes.call
import run.ostrom.server.rules.presentation.NewRuleRequest
import run.ostrom.server.rules.presentation.RuleDto
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The compiled packages a server keeps, on the real rules and the real database: the same sources compile once, a
 * rule written or decided after a package is in the next one, and another organization with the same sources gets a
 * package of its own.
 */
class CompiledPackageFollowsTheRulesPostgresTest {
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)
    private val overrides = module {
        includes(RulesCallers.overrides(), RulesCallers.gateSessions)
    }

    private suspend fun TestServer.current(orgId: Uuid): ServedPackage =
        koin.get<PublishPackageUseCase>()(orgId, REPO, "https://ostrom.example", "lyes")

    // A decision's receipt is an input of the harness: the request signs it before it answers
    // (SignDecisionsUseCase, called by the audit of the change). Checked, so that a count cannot be off by a late one.
    private suspend fun TestServer.everyDecisionSigned() {
        val decisions = koin.get<RulesRepository>().lastRuleDecisions(RulesCallers.orgA)
        assertTrue(decisions.isNotEmpty() && decisions.all { it.signed }, "decisions signed: $decisions")
    }

    private fun ServedPackage.harness(): JsonElement? =
        Json.parseToJsonElement(stored.envelope.payload).jsonObject["harness"]

    @Test
    fun aRuleWrittenOrDecidedAfterAPackageIsInTheNextOneAndNoOrganizationGetsAnothers() = serverTest(
        modules,
        overrides,
    ) { server ->
        val packages = server.koin.get<CompiledPackages>()
        val first = server.current(RulesCallers.orgA)
        assertEquals(first.stored, server.current(RulesCallers.orgA).stored)
        assertEquals(1, packages.compilations, "The same sources are compiled once")

        val theirs = server.current(RulesCallers.orgB)
        assertEquals(2, packages.compilations, "Another organization's sources, the same, are compiled again")
        // The same sources at the same time give the same harness version and receipt id, so they cannot tell the
        // organizations apart: B's harness stored under B can, as a body taken from A's compilation stores none.
        val digest = theirs.harness()?.jsonObject?.get("digest")?.jsonPrimitive?.content.orEmpty()
        val stamp = server.koin.get<CompilerRepository>().harness(RulesCallers.orgB, REPO, digest)
        assertNotNull(stamp, "Its harness is signed and stored for it, not taken from the first")

        val created = server.call("POST", "/api/v1/rules", "admin-a", RULE)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        server.everyDecisionSigned()
        val proposed = server.current(RulesCallers.orgA)
        assertEquals(3, packages.compilations, "A rule written is a new input")
        assertNotEquals(first.stored.envelope.contentDigest, proposed.stored.envelope.contentDigest)

        val id = created.body<RuleDto>().id
        val accept = buildJsonObject { put("status", "accepted") }
        val decided = server.call("PATCH", "/api/v1/rules/$id", "admin-a", accept)
        assertEquals(HttpStatusCode.OK, decided.status, decided.bodyAsText())
        server.everyDecisionSigned()
        val accepted = server.current(RulesCallers.orgA)
        assertEquals(4, packages.compilations, "A rule decided is a new input")
        assertNotEquals(proposed.stored.envelope.contentDigest, accepted.stored.envelope.contentDigest)
        assertEquals(accepted.stored, server.current(RulesCallers.orgA).stored)
        assertEquals(4, packages.compilations)
    }

    private companion object {
        const val REPO = "ostrom-hq/gate-demo"
        val RULE = NewRuleRequest(
            statement = "Every migration is reviewed by the data team.",
            group = "Data",
            status = "proposed",
        )
    }
}
