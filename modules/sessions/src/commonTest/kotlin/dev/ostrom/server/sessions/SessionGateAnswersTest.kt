// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions

import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.gates.domain.usecases.SessionGateDecision
import dev.ostrom.server.sessions.domain.models.GateAnswer
import dev.ostrom.server.sessions.domain.models.StartSession
import dev.ostrom.server.sessions.domain.usecases.answerOf
import dev.ostrom.server.sessions.fakes.SessionBench
import dev.ostrom.server.sessions.fakes.agent
import dev.ostrom.server.sessions.fakes.user
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The thread of a session (`ostrom logs`) carries the answer to each of its gates: yes or no, who, the receipt. */
class SessionGateAnswersTest {
    private val at = Instant.parse("2026-11-02T22:00:00Z")
    private val yes = SessionGateDecision(Uuid.random(), "rev-b", at, approved = true, selfApproved = false)
    private val no = SessionGateDecision(Uuid.random(), "rev-c", at, approved = false, selfApproved = false)

    private fun gate(n: Int, status: GateStatus, vararg decisions: SessionGateDecision, receipt: String? = null) =
        SessionGate(
            Uuid.parse("00000000-0000-0000-0000-0000000000a$n"),
            "push-main",
            GateMode.ENFORCED,
            status,
            receipt,
            decisions.toList(),
        )

    @Test
    fun aYesStaysAYesOnceReservedOrUsedAndANoIsARefusal() {
        for (status in listOf(GateStatus.APPROVED, GateStatus.RESERVED, GateStatus.CONSUMED)) {
            val answer = answerOf(gate(1, status, no, yes, receipt = "rcpt_gate_1"))
            assertEquals(
                GateAnswer("00000000-0000-0000-0000-0000000000a1", "push-main", true, "rev-b", at, "rcpt_gate_1"),
                answer,
                "Who said the yes, never the approver who said no",
            )
        }
        val refused = answerOf(gate(2, GateStatus.REJECTED, no))
        assertEquals(listOf(false, "rev-c", null), listOf(refused?.approved, refused?.by, refused?.receiptId))
    }

    @Test
    fun aGateNobodyAnsweredHasNoAnswer() {
        assertNull(answerOf(gate(3, GateStatus.PENDING)), "Waiting")
        assertNull(answerOf(gate(4, GateStatus.PENDING, no)), "A no that did not decide it")
        assertNull(answerOf(gate(5, GateStatus.SHADOW)), "Recorded without blocking")
        assertNull(answerOf(gate(6, GateStatus.UNGATED)), "No gate applies")
        assertNull(answerOf(gate(7, GateStatus.APPROVED)), "A decided gate whose answers are not read")
    }

    @Test
    fun aSessionIsReadWithTheAnswersToItsGatesOnly() = runTest {
        val bench = SessionBench()
        bench.start(agent(), StartSession(id = "s-1", agent = "claude-code", repo = "acme/api"))
        bench.start(agent("launch-2"), StartSession(id = "s-2", agent = "claude-code", repo = "acme/api"))
        bench.gates.gates["s-1"] = listOf(gate(1, GateStatus.CONSUMED, yes), gate(3, GateStatus.PENDING))
        bench.gates.gates["s-2"] = listOf(gate(2, GateStatus.REJECTED, no))

        val first = bench.get(user(), "s-1")
        assertEquals(2, first.gates.size, "Both gates are listed")
        assertEquals(listOf(true to "rev-b"), first.answers.map { it.approved to it.by }, "Only the answered one")
        assertEquals(listOf(false to "rev-c"), bench.get(user(), "s-2").answers.map { it.approved to it.by })
    }
}
