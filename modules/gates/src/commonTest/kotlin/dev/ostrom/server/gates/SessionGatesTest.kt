// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.usecases.ListSessionGatesUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.SessionGateDecision
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.OTHER_SESSION
import dev.ostrom.server.gates.fakes.SESSION
import dev.ostrom.server.gates.fakes.SHA_B
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class SessionGatesTest {
    @Test
    fun aSessionListsItsGatesWithTheAnswerThatDecidedThem() = runTest {
        val bench = GateBench()
        val approved = bench.open(bench.pushRequest())
        bench.answer(approved, "rev-b")
        val rejected = bench.open(bench.pushRequest(sha = SHA_B))
        bench.answer(rejected, "rev-b", Answer.NO)
        val pending = bench.open(bench.pushRequest(sha = "c".repeat(40)))
        val shadow = bench.open(bench.pushRequest(sha = "d".repeat(40), node = bench.node("lyes", enforced = false)))
        bench.open(bench.pushRequest(session = OTHER_SESSION, diff = "elsewhere"))

        val gates = ListSessionGatesUseCaseImpl(bench.repository)(approved.gate.orgId, SESSION)

        assertEquals(listOf(approved, rejected, pending, shadow).map { it.gate.id }, gates.map { it.id })
        assertEquals(
            listOf(GateStatus.APPROVED, GateStatus.REJECTED, GateStatus.PENDING, GateStatus.SHADOW),
            gates.map { it.status },
        )
        assertEquals(GateMode.SHADOW, gates[3].mode)
        assertEquals("push-main", gates[0].actionClass)
        assertEquals("rcpt_${approved.gate.id}", gates[0].receiptId)
        val revB = approved.requests.first { it.approverId == "rev-b" }.id
        assertEquals(
            listOf(SessionGateDecision(revB, "rev-b", bench.clock.now, approved = true, selfApproved = false)),
            gates[0].decisions,
        )
        assertEquals(listOf(false), gates[1].decisions.map { it.approved })
        assertEquals(emptyList(), gates[2].decisions)
        // The answers of every decided gate come in one read, never one per gate.
        assertEquals(1, bench.repository.batchedRequestReads)
        assertEquals(emptyList(), ListSessionGatesUseCaseImpl(bench.repository)(Uuid.random(), SESSION))
    }
}
