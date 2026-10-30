// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.core.api.models.CheckOutcome
import dev.ostrom.core.api.models.ReportCheckRequest
import dev.ostrom.server.receipts.domain.models.Verification
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCase
import dev.ostrom.server.sessions.domain.models.NotYourSessionException
import dev.ostrom.server.workflowruns.domain.models.InvalidRunException
import dev.ostrom.server.workflowruns.domain.models.RunPackageNotFoundException
import dev.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import dev.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.WorkflowReceiptRequest
import dev.ostrom.server.workflowruns.fakes.AGENT
import dev.ostrom.server.workflowruns.fakes.BOB
import dev.ostrom.server.workflowruns.fakes.FakeSessions
import dev.ostrom.server.workflowruns.fakes.FixedClock
import dev.ostrom.server.workflowruns.fakes.InMemoryRuns
import dev.ostrom.server.workflowruns.fakes.ORG
import dev.ostrom.server.workflowruns.fakes.OTHER_AGENT
import dev.ostrom.server.workflowruns.fakes.access
import dev.ostrom.server.workflowruns.fakes.gate
import dev.ostrom.server.workflowruns.fakes.session
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WorkflowReceiptTest {
    private val runs = InMemoryRuns()
    private val signed = mutableListOf<Pair<JsonObject, String?>>()
    private val signer = object : SignReceiptUseCase {
        override suspend fun invoke(orgId: Uuid, draft: JsonObject, id: String?): JsonObject {
            assertEquals(ORG, orgId)
            signed += draft to id
            return JsonObject(draft + mapOf("id" to JsonPrimitive(id), "signature" to JsonPrimitive("sig")))
        }
    }
    private val verifier = object : VerifyReceiptUseCase {
        override suspend fun invoke(orgId: Uuid, id: String): Verification = error("Verified as signed.")

        override suspend fun verify(receipt: JsonObject): Verification =
            Verification(receipt["signature"]?.jsonPrimitive?.content == "sig", "d", "k1", null)
    }
    private val sessions = FakeSessions(gates = listOf(gate(approved = true, self = true), gate(false, false)))
    private val sign = SignWorkflowReceiptUseCaseImpl(access(sessions), sessions, runs, signer, verifier)

    private fun request(agent: String? = null, model: String? = null, sessionId: String = "s-1") =
        WorkflowReceiptRequest(sessionId, "sha256:pkg", agent, model)

    @Test
    fun theReceiptSaysWhatEachNodeDidUnderTheSessionsPackageAndGates() = runTest {
        ReportCheckUseCaseImpl(access(), runs, FixedClock())(
            AGENT,
            ReportCheckRequest("s-1", "sha256:pkg", "n-check", "sc-1", "sha-good", 0, CheckOutcome.PASSED),
        )
        val receipt = sign(AGENT, request())
        assertTrue(receipt.verification.ok)
        val (draft, id) = signed.single()
        assertEquals("rcpt_workflow_s-1", id, "Signed once per session: asked again, the same receipt")
        val subject = draft.getValue("subject").jsonObject
        assertEquals("workflow" to "s-1@v3", subject.text("kind") to subject.text("id"))
        assertTrue(Regex("[0-9a-f]{64}").matches(subject.text("digest")), subject.text("digest"))
        assertEquals("ada" to "claude-code", draft.text("initiator") to draft.text("agent"))
        assertNull(draft["model"])
        assertEquals(JsonArray(emptyList()), draft["rules"])
        val pack = draft.getValue("package").jsonObject
        assertEquals("v3" to "sha256:pkg", pack.text("version") to pack.text("digest"))
        val nodes = draft.getValue("nodes").jsonArray.map { it.jsonObject.text("outcome") }
        assertEquals(listOf("passed", "not triggered"), nodes)
        val decisions = draft.getValue("decisions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("approved", "rejected"), decisions.map { it.text("result") })
        assertEquals(listOf("lyes", "lyes"), decisions.map { it.text("by") })
        assertEquals(JsonPrimitive(true), decisions.first()["selfApproved"])
        assertFalse("selfApproved" in decisions.last(), "Written only when true")
        assertEquals("2026-10-30T10:00:00.000Z", decisions.first().text("at"))
    }

    @Test
    fun theAgentAndModelTheLauncherNamesAreKept() = runTest {
        sign(AGENT, request(agent = "codex", model = "gpt-5.1-codex"))
        val draft = signed.single().first
        assertEquals("codex" to "gpt-5.1-codex", draft.text("agent") to draft.text("model"))
    }

    @Test
    fun onlyTheSessionsLaunchAndPersonSignItsReceipt() = runTest {
        assertFailsWith<NotYourSessionException> { sign(OTHER_AGENT, request()) }
        assertFailsWith<NotYourSessionException> { sign(BOB, request()) }
        assertFailsWith<RunSessionNotFoundException> { sign(AGENT, request(sessionId = "s-9")) }
        assertFailsWith<RunPackageNotFoundException> { sign(AGENT, request().copy(digest = "sha256:other")) }
        assertFailsWith<InvalidRunException> { sign(AGENT, request(agent = "a\nb")) }
        assertFailsWith<InvalidRunException> { sign(AGENT, request(model = "m".repeat(201))) }
        assertTrue(signed.isEmpty())
        val alone = FakeSessions(listOf(session()))
        val ownSession = SignWorkflowReceiptUseCaseImpl(access(alone), alone, runs, signer, verifier)
        assertEquals(0, ownSession(AGENT, request()).receipt.getValue("decisions").jsonArray.size)
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}
