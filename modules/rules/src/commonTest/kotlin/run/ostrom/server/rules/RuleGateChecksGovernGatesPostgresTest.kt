// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.services.GateCheckNeed
import run.ostrom.server.gates.domain.services.GateCheckNeeds
import run.ostrom.server.gates.domain.services.GateCheckProof
import run.ostrom.server.gates.domain.services.GateCheckProofs
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.presentation.GateAskRequest
import run.ostrom.server.gates.presentation.GatePush
import run.ostrom.server.gates.presentation.GatePushRef
import run.ostrom.server.gates.presentation.GateResponse
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.rules.domain.repositories.RulesRepository
import run.ostrom.server.rules.fakes.RulesCallers
import run.ostrom.server.rules.fakes.RulesCallers.orgA
import run.ostrom.server.rules.fakes.RulesCallers.orgB
import run.ostrom.server.rules.fakes.call
import run.ostrom.server.rules.infrastructure.RulesCompilerSources
import run.ostrom.server.rules.infrastructure.RulesGateCheckNeeds
import run.ostrom.server.rules.presentation.GateActionDto
import run.ostrom.server.rules.presentation.NewRuleRequest
import run.ostrom.server.rules.presentation.RuleDto
import run.ostrom.server.rules.presentation.RuleGateCheckDto
import run.ostrom.server.rules.presentation.RuleGateDto
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * A rule whose gate needs a check attested (decision 13:23, core#40) governs the real gates: a push whose check is not
 * attested is refused before anyone is asked, and passes to the approvers once the runner or the CI ran it.
 */
class RuleGateChecksGovernGatesPostgresTest {
    private var ruleId = ""
    private var proof = GateCheckProof(attestation = null, passed = false)
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)
    private val overrides = module {
        includes(RulesCallers.overrides(), RulesCallers.gateSessions)
        // The catalogue's node of the rule's gate, as the compiler names it.
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) = GateNode(
                    "${RulesCompilerSources.WORKFLOW_PREFIX}$ruleId",
                    RulesCompilerSources.NODE,
                    "Tests before a push to main",
                    listOf("lyes"),
                    enforced = true,
                )
            }
        }
        single<GateCheckProofs> {
            object : GateCheckProofs {
                override suspend fun of(orgId: Uuid, sessionId: String?, subject: GateSubject, check: String) = proof
            }
        }
    }

    private val gate = RuleGateDto(
        listOf(GateActionDto("push-main", "Push to main")),
        listOf("lyes"),
        checks = listOf(RuleGateCheckDto("tests", attested = true), RuleGateCheckDto("lint")),
    )

    private suspend fun TestServer.push(sha: String) = call(
        "POST",
        "/api/v1/gates",
        "agent-a",
        GateAskRequest(
            sessionId = "claude-5e5",
            repo = "ostrom-hq/gate-demo",
            cls = "push-main",
            command = "git push origin main",
            push = GatePush("git@github.com:ostrom-hq/gate-demo.git", listOf(GatePushRef("refs/heads/main", sha))),
            diff = "+ok",
        ),
    )

    @Test
    fun aPushWhoseCheckIsOnlyDeclaredIsRefusedThenPassesOnceAttested() = serverTest(modules, overrides) { server ->
        assertIs<RulesGateCheckNeeds>(server.koin.get<GateCheckNeeds>())
        val created = server.call("POST", "/api/v1/rules", "admin-a", NewRuleRequest("Tests first", "git", gate = gate))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        ruleId = created.body<RuleDto>().id

        proof = GateCheckProof(CheckAttestation.WORKSTATION_DECLARED, passed = true)
        val declared = server.push(SHA_A)
        assertEquals(HttpStatusCode.Conflict, declared.status, declared.bodyAsText())
        val body = declared.bodyAsText()
        assertTrue("\"type\":\"check_not_attested\"" in body, body)
        assertTrue("tests was only declared by the workstation (workstation_declared)" in body, body)
        assertTrue("lint" !in body, "lint needs no attestation: $body")

        proof = GateCheckProof(CheckAttestation.CI, passed = true)
        val attested = server.push(SHA_A)
        assertTrue(attested.status.value in 200..201, attested.bodyAsText())
        assertEquals("pending", attested.body<GateResponse>().gate.status, "Now the approvers are asked")
    }

    @Test
    fun aGateNeedsOnlyTheChecksOfItsOwnOrganizationsRule() = serverTest(modules, overrides) { server ->
        val needs = RulesGateCheckNeeds(server.koin.get<RulesRepository>())
        val created = server.call("POST", "/api/v1/rules", "admin-a", NewRuleRequest("Tests first", "git", gate = gate))
        val id = created.body<RuleDto>().id
        fun node(workflowId: String) = GateNode(workflowId, RulesCompilerSources.NODE, "Tests", listOf("lyes"), true)
        val ofRule = node("${RulesCompilerSources.WORKFLOW_PREFIX}$id")

        assertEquals(listOf(GateCheckNeed("tests", true), GateCheckNeed("lint", false)), needs.of(orgA, ofRule))
        assertEquals(emptyList<GateCheckNeed>(), needs.of(orgB, ofRule), "Another organization reads no rule of A")
        assertEquals(emptyList<GateCheckNeed>(), needs.of(orgA, node("ship")), "A workflow's own gate")
        assertEquals(emptyList<GateCheckNeed>(), needs.of(orgA, node("rule:rule_gone")), "A rule deleted since")

        val ungated = server.call("PATCH", "/api/v1/rules/$id", "admin-a", buildJsonObject { put("gate", JsonNull) })
        assertEquals(HttpStatusCode.OK, ungated.status, ungated.bodyAsText())
        assertEquals(emptyList<GateCheckNeed>(), needs.of(orgA, ofRule), "A rule whose gate was removed since")
    }

    private companion object {
        const val SHA_A = "0123456789abcdef0123456789abcdef01234567"
    }
}
