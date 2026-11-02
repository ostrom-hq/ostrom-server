// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.presentation.GateAskRequest
import dev.ostrom.server.gates.presentation.GatePush
import dev.ostrom.server.gates.presentation.GatePushRef
import dev.ostrom.server.gates.presentation.GateResponse
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.rules.fakes.RulesCallers
import dev.ostrom.server.rules.fakes.call
import dev.ostrom.server.rules.infrastructure.RulesCompilerSources
import dev.ostrom.server.rules.presentation.GateActionDto
import dev.ostrom.server.rules.presentation.NewRuleRequest
import dev.ostrom.server.rules.presentation.RuleDto
import dev.ostrom.server.rules.presentation.RuleGateDto
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rule written in the console changes what the real gates decide: the compiler's catalogue reads the rule's gate,
 * the gates module asks that catalogue. Ungated before the rule, shadow while it is proposed, enforced with its
 * approvers once accepted, ungated again once rejected.
 */
class RuleGovernsGatesPostgresTest {
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)
    private val overrides = module {
        includes(RulesCallers.overrides(), RulesCallers.gateSessions)
    }

    private fun push(sha: String) = GateAskRequest(
        sessionId = "claude-5e5",
        repo = "ostrom-hq/gate-demo",
        cls = "push-main",
        command = "git push origin main",
        push = GatePush("git@github.com:ostrom-hq/gate-demo.git", listOf(GatePushRef("refs/heads/main", sha))),
        diff = "+ok",
    )

    private suspend fun TestServer.ask(sha: String): GateResponse {
        val response = call("POST", "/api/v1/gates", "agent-a", push(sha))
        assertTrue(response.status.value in 200..201, response.bodyAsText())
        return response.body<GateResponse>()
    }

    private suspend fun TestServer.decide(id: String, status: String) {
        val body = buildJsonObject { put("status", status) }
        val response = call("PATCH", "/api/v1/rules/$id", "admin-a", body)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private val rule = NewRuleRequest(
        statement = "Every push to main waits for Mia's yes.",
        group = "Shipping",
        status = "proposed",
        gate = RuleGateDto(
            actions = listOf(GateActionDto(cls = "push-main", label = "Push to main")),
            approvers = listOf("mia"),
            reason = "Mia owns releases",
        ),
    )

    @Test
    fun aRuleWrittenInTheConsoleChangesTheGateOfAPushToMain() = serverTest(modules, overrides) { server ->
        val before = server.ask("0000000000000000000000000000000000000001")
        assertEquals("ungated", before.gate.mode, "No workflow and no rule: nothing gates a push to main")

        val created = server.call("POST", "/api/v1/rules", "admin-a", rule)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = created.body<RuleDto>().id

        val proposed = server.ask("0000000000000000000000000000000000000002")
        assertEquals("shadow", proposed.gate.mode, "A proposed rule only records the push")
        assertEquals("${RulesCompilerSources.WORKFLOW_PREFIX}$id", proposed.gate.gate?.workflowId)

        server.decide(id, "accepted")
        val accepted = server.ask("0000000000000000000000000000000000000003")
        assertEquals("enforced", accepted.gate.mode, "An accepted rule enforces its gate")
        assertEquals("pending", accepted.gate.status)
        assertEquals(listOf("mia"), accepted.waitingFor, "The rule's approvers answer, not the asker")
        assertEquals("Mia owns releases", accepted.gate.gate?.reason)

        val catalog = server.call("GET", "/api/v1/gates/catalog?repo=ostrom-hq/gate-demo", "agent-a")
            .body<JsonObject>()
        val gated = catalog.getValue("actions").jsonArray.map { it.jsonObject }
            .single { it.getValue("class").jsonPrimitive.content == "push-main" }
        val hooks = gated.getValue("gate").jsonObject
        assertEquals("${RulesCompilerSources.WORKFLOW_PREFIX}$id", hooks.getValue("workflowId").jsonPrimitive.content)
        assertEquals("true", hooks.getValue("enforce").jsonPrimitive.content, "The hooks hold the same gate")

        server.decide(id, "rejected")
        val rejected = server.ask("0000000000000000000000000000000000000004")
        assertEquals("ungated", rejected.gate.mode, "A rejected rule gates nothing")
    }
}
