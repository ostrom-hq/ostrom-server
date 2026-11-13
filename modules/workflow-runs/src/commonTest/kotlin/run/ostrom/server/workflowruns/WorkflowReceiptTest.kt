// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.CheckOutcome
import run.ostrom.core.api.models.CheckRun
import run.ostrom.core.api.models.PackageMode
import run.ostrom.core.api.models.ReportCheckRequest
import run.ostrom.server.gates.domain.models.CiCheck
import run.ostrom.server.gates.domain.services.CiChecks
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.models.Verification
import run.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import run.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCase
import run.ostrom.server.sessions.domain.models.NotYourSessionException
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.models.RunPackageNotFoundException
import run.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import run.ostrom.server.workflowruns.domain.models.SessionStillOpenException
import run.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.WorkflowReceiptRequest
import run.ostrom.server.workflowruns.fakes.ADA
import run.ostrom.server.workflowruns.fakes.AGENT
import run.ostrom.server.workflowruns.fakes.BOB
import run.ostrom.server.workflowruns.fakes.FakeSessions
import run.ostrom.server.workflowruns.fakes.FixedClock
import run.ostrom.server.workflowruns.fakes.InMemoryRuns
import run.ostrom.server.workflowruns.fakes.ORG
import run.ostrom.server.workflowruns.fakes.OTHER_AGENT
import run.ostrom.server.workflowruns.fakes.access
import run.ostrom.server.workflowruns.fakes.gate
import run.ostrom.server.workflowruns.fakes.session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        override suspend fun invoke(reader: ReceiptReader, id: String): Verification = error("Verified as signed.")

        override suspend fun verify(orgId: Uuid, receipt: JsonObject): Verification {
            assertEquals(ORG, orgId, "Verified for the caller's organization")
            return Verification(receipt["signature"]?.jsonPrimitive?.content == "sig", "d", "k1", null, true)
        }
    }
    private val sessions = FakeSessions(
        listOf(session(ended = true)),
        gates = listOf(gate(approved = true, self = true), gate(false, false)),
    )
    private val ci = RecordingCi()
    private val sign = SignWorkflowReceiptUseCaseImpl(access(sessions), sessions, runs, signer, verifier, ci)

    private fun request(agent: String? = null, model: String? = null, sessionId: String = "s-1") =
        WorkflowReceiptRequest(sessionId, "sha256:pkg", agent, model)

    @Test
    fun theReceiptSaysWhatEachNodeDidUnderTheSessionsPackageAndGates() = runTest {
        ReportCheckUseCaseImpl(access(), runs, FixedClock())(
            AGENT,
            ReportCheckRequest("s-1", "sha256:pkg", "n-check", "sc-1", "sha-good", 0, CheckOutcome.PASSED),
        )
        val receipt = sign(AGENT, request())
        assertTrue(receipt.verification.ok && receipt.verification.orgBound)
        val (draft, id) = signed.single()
        assertEquals("lch_1" to "s-1", draft.text("launchId") to draft.text("sessionId"), "Its launch and session")
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
    fun aSessionOutsideALaunchNamesNoLaunch() = runTest {
        val unlaunched = FakeSessions(listOf(session(ended = true).copy(launchId = null)))
        SignWorkflowReceiptUseCaseImpl(access(unlaunched), unlaunched, runs, signer, verifier, ci)(ADA, request())
        val draft = signed.single().first
        assertNull(draft["launchId"])
        assertEquals("s-1", draft.text("sessionId"))
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
        val open = FakeSessions(listOf(session()))
        val early = SignWorkflowReceiptUseCaseImpl(access(open), open, runs, signer, verifier, ci)
        assertFailsWith<SessionStillOpenException> { early(AGENT, request()) }
        assertTrue(signed.isEmpty(), "An open session's receipt would miss what it reports next")
        val alone = FakeSessions(listOf(session(ended = true)))
        val ownSession = SignWorkflowReceiptUseCaseImpl(access(alone), alone, runs, signer, verifier, ci)
        assertEquals(0, ownSession(AGENT, request()).receipt.getValue("decisions").jsonArray.size)
    }

    @Test
    fun aCheckTheCiRanOnTheSameCommitIsSignedAsProvenByTheCi() = runTest {
        runs.checks += ran(head = SHA)
        ci.answer = CiCheck(77, "Label n-check", SHA, "success", "github-actions", "https://github.com/r/77", null)
        val node = sign(AGENT, request()).receipt.getValue("nodes").jsonArray.first().jsonObject
        assertEquals("ci" to "passed in CI", node.text("attestation") to node.text("outcome"))
        assertEquals("https://github.com/r/77", node.text("ref"))
        assertEquals(listOf(listOf("acme/api", SHA, "Label n-check")), ci.asked, "Only the check node, once")
    }

    @Test
    fun theForgeIsAskedOnlyForARunsCommitInTheRepositoryTheSessionRunsIn() = runTest {
        sign(AGENT, request())
        assertTrue(ci.asked.isEmpty(), "No run of the check")
        runs.checks += ran(head = null)
        sign(AGENT, request())
        assertTrue(ci.asked.isEmpty(), "A run that named no commit")
        runs.checks += ran(head = SHA)
        val nowhere = FakeSessions(listOf(session(ended = true, repo = null)))
        SignWorkflowReceiptUseCaseImpl(access(nowhere), nowhere, runs, signer, verifier, ci)(AGENT, request())
        assertTrue(ci.asked.isEmpty(), "A session in no repository")
        val moved = FakeSessions(listOf(session(ended = true).copy(realRepo = "acme/fork")))
        val node = SignWorkflowReceiptUseCaseImpl(access(moved), moved, runs, signer, verifier, ci)(AGENT, request())
            .receipt.getValue("nodes").jsonArray.first().jsonObject
        assertEquals(listOf(listOf("acme/fork", SHA, "Label n-check")), ci.asked, "Where it really runs")
        assertEquals("workstation_declared", node.text("attestation"), "No CI run: declared")
    }

    private fun ran(head: String?) = CheckRun(
        id = "chk_${runs.checks.size}",
        at = "2026-10-30T10:00:00.000Z",
        sessionId = "s-1",
        digest = "sha256:pkg",
        nodeId = "n-check",
        scriptId = "sc-1",
        mode = PackageMode.ENFORCE,
        exit = 0,
        head = head,
        sandbox = "bwrap",
        dev = "ada",
    )

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}

/** The forge's record of CI runs: one answer for every question, and the questions asked. */
private class RecordingCi : CiChecks {
    val asked = mutableListOf<List<String>>()
    var answer: CiCheck? = null

    override suspend fun latest(orgId: Uuid, repo: String, sha: String, name: String): CiCheck? {
        asked += listOf(repo, sha, name)
        return answer
    }
}

private const val SHA = "0123456789abcdef0123456789abcdef01234567"

