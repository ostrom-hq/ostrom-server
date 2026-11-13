// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.presentation.AnswerApprovalRequest
import run.ostrom.server.gates.presentation.ApprovalAnswer
import run.ostrom.server.gates.presentation.ApprovalsPage
import run.ostrom.server.gates.presentation.GateAskRequest
import run.ostrom.server.gates.presentation.GatePush
import run.ostrom.server.gates.presentation.GatePushRef
import run.ostrom.server.gates.presentation.GateResponse
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.rules.fakes.RulesCallers
import run.ostrom.server.rules.fakes.call
import run.ostrom.server.rules.presentation.GatePolicyDto
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The self-approval policy set in the console governs the real gates: by default (`never`) the gate's sole approver
 * cannot approve their own push; once the organization chooses `sole-approver` they can, and `never` again refuses.
 */
class GatePolicyGovernsGatesPostgresTest {
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)
    private val overrides = module {
        includes(RulesCallers.overrides(), RulesCallers.gates)
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

    private suspend fun TestServer.answerAsLyes(gate: GateResponse): HttpResponse {
        val approval = call("GET", "/api/v1/approvals?limit=100", "admin-a").body<ApprovalsPage>().items
            .single { it.group == gate.gate.id }
        return call(
            "POST",
            "/api/v1/approvals/${approval.id}/answer",
            "admin-a",
            AnswerApprovalRequest(ApprovalAnswer.APPROVE, approval.shownDigest),
        )
    }

    @Test
    fun theSoleApproverApprovesTheirOwnPushOnlyOnceThePolicySaysSo() = serverTest(modules, overrides) { server ->
        val byDefault = server.ask("00112233445566778899aabbccddeeff00112233")
        assertEquals(emptyList(), byDefault.waitingFor, "Never by default: nobody else can say yes")
        assertEquals(HttpStatusCode.Forbidden, server.answerAsLyes(byDefault).status, "lyes may not approve their own")

        val chosen = server.call("PUT", "/api/v1/gates/policy", "admin-a", GatePolicyDto("sole-approver"))
        assertEquals(HttpStatusCode.OK, chosen.status, chosen.bodyAsText())
        val first = server.ask("0123456789abcdef0123456789abcdef01234567")
        assertEquals("pending", first.gate.status)
        assertEquals(listOf("lyes"), first.waitingFor, "Chosen by the organization: lyes may say yes to their own push")
        val allowed = server.answerAsLyes(first)
        assertEquals(HttpStatusCode.OK, allowed.status, allowed.bodyAsText())

        val set = server.call("PUT", "/api/v1/gates/policy", "admin-a", GatePolicyDto("never"))
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())

        val second = server.ask("fedcba9876543210fedcba9876543210fedcba98")
        assertEquals("pending", second.gate.status)
        assertEquals(emptyList(), second.waitingFor, "Nobody can say yes: add an approver")
        val refused = server.answerAsLyes(second)
        assertEquals(HttpStatusCode.Forbidden, refused.status, "The console's never now governs the gate")
    }
}
