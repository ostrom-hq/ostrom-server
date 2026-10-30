// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.core.receipts.Digests
import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.InvalidGateRequestException
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.OTHER_SESSION
import dev.ostrom.server.gates.infrastructure.ReceiptsGateSigner
import dev.ostrom.server.receipts.domain.services.ReceiptRule
import dev.ostrom.server.receipts.domain.services.ReceiptRules
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ReceiptsGateSignerTest {
    private class RecordingSign : SignReceiptUseCase {
        val drafts = mutableListOf<Pair<JsonObject, String?>>()

        override suspend fun invoke(orgId: Uuid, draft: JsonObject, id: String?): JsonObject {
            drafts += draft to id
            return JsonObject(draft + ("id" to JsonPrimitive(id)))
        }
    }

    private val rules = object : ReceiptRules {
        override suspend fun rulesFor(orgId: Uuid, repo: String?) =
            listOf(ReceiptRule("r-commit-style", "79b6ac48e5225be9037493c1e8a8fc9e5fbc42e2893b0c15b8846bd39d310cd7"))
    }

    private fun signer(bench: GateBench, sign: RecordingSign) =
        ReceiptsGateSigner(sign, bench.repository, bench.sessions, rules)

    @Test
    fun theGatePreimageIsThePrototypes() = runTest {
        val bench = GateBench()
        val node = GateNode(
            workflowId = "wf-ship",
            nodeId = "wf-ship-gate",
            name = "Push to main",
            approvers = listOf("rev-b", "dev-a"),
            enforced = true,
            workflowName = "Ship acme-api",
            reason = "gate approvers of the workflow",
        )
        val sha = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
        val opened = bench.open(bench.pushRequest()).gate
        val gate = vectorGate(opened, node, GatePush("acme/acme-api", listOf(PushRef("main", sha)), force = false))
        val preimage = signer(bench, RecordingSign()).preimage(gate)
        // `gate-push-sha` of ostrom-amont/mvp/compat/vectors/neutral/receipts.json
        assertEquals("eea507ffc456edcb2ed4bfe52767ed5d635a4ab11882317b401a0f1541e3b05f", Digests.digestOf(preimage))
    }

    @Test
    fun aDecisionIsSignedOnceWithItsAnswerRulesAndAgent() = runTest {
        val bench = GateBench()
        val sign = RecordingSign()
        val view = bench.open(bench.pushRequest())
        val decided = bench.answer(view, "rev-b")

        assertEquals("rcpt_gate_${view.gate.id}", signer(bench, sign).sign(decided.gate))
        val (draft, id) = sign.drafts.single()
        assertEquals("rcpt_gate_${view.gate.id}", id)
        assertEquals(
            Json.parseToJsonElement(
                """{"kind":"gate","id":"${view.gate.id}","digest":"${draft.digest()}"}""",
            ),
            draft["subject"],
        )
        assertEquals("lyes", draft.text("initiator"))
        assertEquals("claude-code", draft.text("agent"))
        assertEquals("r-commit-style", draft.getValue("rules").jsonArray.single().jsonObject.text("id"))
        val decision = draft.getValue("decisions").jsonArray.single().jsonObject
        val request = view.requests.first { it.approverId == "rev-b" }
        assertEquals(request.id.toString(), decision.text("approvalId"))
        assertEquals("rev-b", decision.text("by"))
        assertEquals("approved", decision.text("result"))
        assertEquals("2026-10-30T02:00:00.000Z", decision.text("at"))
        assertFalse("selfApproved" in decision)
    }

    @Test
    fun aRejectionAndASelfApprovalSayWhatTheyAre() = runTest {
        val bench = GateBench()
        val sign = RecordingSign()
        val rejected = bench.answer(bench.open(bench.pushRequest(session = OTHER_SESSION)), "rev-b", Answer.NO)
        signer(bench, sign).sign(rejected.gate)
        val decision = sign.drafts.single().first.getValue("decisions").jsonArray.single().jsonObject
        assertEquals("rejected", decision.text("result"))
        assertEquals("agent", sign.drafts.single().first.text("agent"), "A session the sessions module does not know")

        val self = rejected.gate.copy(selfApproved = true, decidedBy = null, askedBy = emptyList())
        signer(bench, sign).sign(self)
        val last = sign.drafts.last().first
        assertEquals("true", last.getValue("decisions").jsonArray.single().jsonObject.text("selfApproved"))
        assertEquals("rev-b", last.getValue("decisions").jsonArray.single().jsonObject.text("by"))
        assertEquals("agent", last.text("initiator"))
        signer(bench, sign).sign(self.copy(sessionIds = emptyList()))
        assertEquals("agent", sign.drafts.last().first.text("agent"), "No session: no agent to name")
    }

    @Test
    fun aMergeAskedByTheCheckIsSignedForTheCi() = runTest {
        val bench = GateBench()
        val sign = RecordingSign()
        val decided = bench.answer(bench.open(bench.pushRequest()), "rev-b").gate
        val merge = decided.copy(
            askedVia = setOf(AskedVia.CI, AskedVia.HOOK),
            subject = decided.subject.copy(push = null, pullRequest = GatePullRequest(6, "a".repeat(40), "main")),
            node = null,
        )
        signer(bench, sign).sign(merge)
        assertEquals("ci", sign.drafts.single().first.text("agent"))
        val preimage = signer(bench, sign).preimage(merge)
        assertEquals("""{"sha":"${"a".repeat(40)}","pr":"6"}""", preimage.getValue("ci").toString())
        assertEquals("""{"number":"6","sha":"${"a".repeat(40)}","owner":"main"}""", preimage.getValue("pr").toString())
        assertEquals("""["ci","hook"]""", preimage.getValue("askedVia").toString())
        assertEquals("null", preimage.getValue("gate").toString())
        assertNull(preimage["push"])

        val viaHook = signer(bench, sign).preimage(merge.copy(askedVia = setOf(AskedVia.HOOK)))
        assertEquals("null", viaHook.getValue("ci").toString(), "Only the check gives a CI context")
    }

    @Test
    fun aPendingGateHasNoReceipt() = runTest {
        val bench = GateBench()
        val pending = bench.open(bench.pushRequest()).gate
        assertFailsWith<InvalidGateRequestException> { signer(bench, RecordingSign()).sign(pending) }
        val answeredButUndated = bench.answer(bench.open(bench.pushRequest(sha = "c".repeat(40))), "rev-b").gate
        assertFailsWith<InvalidGateRequestException> {
            signer(bench, RecordingSign()).sign(answeredButUndated.copy(decidedAt = null))
        }
    }

    private fun vectorGate(gate: Gate, node: GateNode, push: GatePush): Gate =
        gate.copy(
            node = node,
            sessionIds = listOf("sess-ship"),
            shownDigest = "b326b42c8d6a2bfba69a27948a507b7a44da035861d0e0f399f52a743e58d79c",
            askedVia = setOf(AskedVia.HOOK),
            subject = gate.subject.copy(
                repo = "acme-api",
                actionClass = "push-main",
                command = "git push origin main",
                push = push,
                pullRequest = null,
            ),
        )


    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.digest(): String = getValue("subject").jsonObject.text("digest")
}
