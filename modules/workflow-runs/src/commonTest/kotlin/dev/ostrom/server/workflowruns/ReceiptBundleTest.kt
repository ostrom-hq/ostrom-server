// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.server.receipts.domain.models.ReceiptKey
import dev.ostrom.server.receipts.domain.models.ReceiptNotFoundException
import dev.ostrom.server.receipts.domain.usecases.ListReceiptKeysUseCase
import dev.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCaseImpl
import dev.ostrom.server.workflowruns.fakes.ADA
import dev.ostrom.server.workflowruns.fakes.AGENT
import dev.ostrom.server.workflowruns.fakes.FakePackages
import dev.ostrom.server.workflowruns.fakes.FakeSessions
import dev.ostrom.server.workflowruns.fakes.FixedClock
import dev.ostrom.server.workflowruns.fakes.InMemoryReceipts
import dev.ostrom.server.workflowruns.fakes.OTHER_AGENT
import dev.ostrom.server.workflowruns.fakes.gate
import dev.ostrom.server.workflowruns.fakes.session
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReceiptBundleTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val workflow = json(
        """{"id":"rcpt_workflow_s-1","subject":{"kind":"workflow","id":"s-1@v3","digest":"d"},
        "package":{"version":"v3","digest":"sha256:pkg"},
        "nodes":[{"ref":"chk_1"},{"ref":"rcpt_gate_9"},{"ref":"rcpt_workflow_s-1"},{"id":"no-ref"},"odd",{"ref":7}]}""",
    )
    private val sessionReceipt = json("""{"id":"rcpt_session_s-1","subject":{"kind":"session","id":"s-1"}}""")
    private val gateReceipt = json("""{"id":"rcpt_gate_1","subject":{"kind":"gate","id":"g"}}""")
    private val node = json("""{"id":"rcpt_gate_9","subject":{"kind":"gate","id":"g9"}}""")
    private val loose =
        json("""{"id":"rcpt_task_1","subject":{"kind":"task","id":"t"},"package":{"digest":"sha256:x"}}""")
    private val bare = json("""{"id":"rcpt_bare"}""")
    private val orphan = json("""{"id":"rcpt_session_s-7","subject":{"kind":"session","id":"s-7"}}""")
    private val unsigned = json("""{"id":"rcpt_workflow_s-3","subject":{"kind":"workflow","id":"s-3@v3"}}""")
    private val nameless = json("""{"id":"rcpt_workflow_x","subject":{"kind":"workflow"}}""")
    private val receipts = InMemoryReceipts(
        "rcpt_workflow_s-1" to workflow,
        "rcpt_session_s-1" to sessionReceipt,
        "rcpt_gate_1" to gateReceipt,
        "rcpt_gate_9" to node,
        "rcpt_task_1" to loose,
        "rcpt_bare" to bare,
        "rcpt_session_s-7" to orphan,
        "rcpt_workflow_s-3" to unsigned,
        "rcpt_workflow_x" to nameless,
    )
    private val keys = object : ListReceiptKeysUseCase {
        override suspend fun invoke(): List<ReceiptKey> = listOf(ReceiptKey("k1", "Ed25519", "PEM"))
    }
    private val sessions = FakeSessions(
        listOf(session(receiptId = "rcpt_session_s-1"), session(id = "s-3")),
        gates = listOf(gate(true, false, receiptId = "rcpt_gate_1"), gate(false, false, receiptId = null)),
    )
    private val bundle = GetReceiptBundleUseCaseImpl(receipts, keys, sessions, FakePackages(), FixedClock())

    @Test
    fun aWorkflowReceiptComesWithItsSessionsGatesAndNodesReceiptsAndItsPackage() = runTest {
        val gathered = bundle(AGENT, "rcpt_workflow_s-1")
        assertEquals(workflow, gathered.receipt)
        assertEquals(listOf(node, sessionReceipt, gateReceipt), gathered.chain, "Each once, never itself")
        assertEquals(false, gathered.envelope?.pinned, "The envelope as signed, not as pinned today")
        assertEquals("sha256:pkg", gathered.envelope?.digest)
        assertEquals(listOf(ReceiptKey("k1", "Ed25519", "PEM")), gathered.keys)
        assertEquals("2026-10-30T10:05:00.123Z", gathered.exportedAt)
    }

    @Test
    fun aSessionReceiptCitesItsGatesAndAReceiptAboutNoSessionCitesItsOwnPieces() = runTest {
        assertEquals(listOf(gateReceipt), bundle(AGENT, "rcpt_session_s-1").chain)
        val task = bundle(ADA, "rcpt_task_1")
        assertEquals(emptyList(), task.chain)
        assertNull(task.envelope, "A package the organization does not have")
        assertNull(bundle(ADA, "rcpt_bare").envelope)
        assertEquals(emptyList(), bundle(ADA, "rcpt_session_s-7").chain, "Its session is gone")
        assertEquals(listOf(gateReceipt), bundle(ADA, "rcpt_workflow_s-3").chain, "Unsigned session: its gates only")
        assertEquals(emptyList(), bundle(ADA, "rcpt_workflow_x").chain, "A workflow receipt about no session")
    }

    @Test
    fun anAgentTokenReadsItsOwnLaunchsReceiptsOnly() = runTest {
        assertFailsWith<ReceiptNotFoundException> { bundle(OTHER_AGENT, "rcpt_workflow_s-1") }
        assertFailsWith<ReceiptNotFoundException> { bundle(AGENT, "rcpt_task_1") }
        assertFailsWith<ReceiptNotFoundException> { bundle(AGENT, "rcpt_bare") }
        assertFailsWith<ReceiptNotFoundException> { bundle(AGENT, "rcpt_session_s-7") }
        assertFailsWith<ReceiptNotFoundException> { bundle(ADA, "rcpt_absent") }
    }
}
