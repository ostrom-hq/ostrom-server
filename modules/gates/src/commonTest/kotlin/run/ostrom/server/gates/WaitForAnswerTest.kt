// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.GateNotFoundException
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.usecases.GetGateUseCase
import run.ostrom.server.gates.domain.usecases.GetGateUseCaseImpl
import run.ostrom.server.gates.fakes.GateBench
import run.ostrom.server.gates.fakes.OTHER_ORG
import run.ostrom.server.gates.fakes.SHA_B
import run.ostrom.server.gates.fakes.agent
import run.ostrom.server.gates.fakes.user
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** `GET /gates/{id}?wait=`: the hook waits on the server for its answer instead of asking again every second. */
class WaitForAnswerTest {
    // The bench's read, on the test's virtual time.
    private fun TestScope.reads(bench: GateBench) =
        GetGateUseCaseImpl(bench.repository, bench.policies, bench.receipts, bench.changes, testTimeSource)

    @Test
    fun aWaitingReadAnswersAsSoonAsTheGateIsAnswered() = runTest {
        val bench = GateBench()
        val pending = bench.open(bench.pushRequest())
        val start = testTimeSource.markNow()
        val waiting = async { reads(bench)(agent(), pending.gate.id, 25.seconds) }
        advanceTimeBy(3.seconds)
        assertFalse(waiting.isCompleted, "nothing was answered yet")
        bench.answer(pending, "rev-b")
        runCurrent()
        assertEquals(GateStatus.APPROVED, waiting.await().gate.status)
        assertEquals(3.seconds, start.elapsedNow(), "woken by the answer, not at the next look")
    }

    @Test
    fun anAnswerGivenOnAnotherInstanceIsSeenAtTheNextLook() = runTest {
        val bench = GateBench()
        val pending = bench.open(bench.pushRequest())
        val start = testTimeSource.markNow()
        val waiting = async { reads(bench)(agent(), pending.gate.id, 25.seconds) }
        advanceTimeBy(500.milliseconds)
        // Another instance wrote the decision: no wake-up here.
        bench.repository.gates[pending.gate.id] = pending.gate.copy(status = GateStatus.REJECTED)
        assertEquals(GateStatus.REJECTED, waiting.await().gate.status)
        assertEquals(GetGateUseCase.LOOK_EVERY, start.elapsedNow())
    }

    @Test
    fun aWaitEndsAtItsCapWithTheGateStillPending() = runTest {
        val bench = GateBench()
        val pending = bench.open(bench.pushRequest())
        val reads = reads(bench)
        for ((asked, waited) in listOf(5.seconds to 5.seconds, 10.minutes to GetGateUseCase.MAX_WAIT)) {
            val start = testTimeSource.markNow()
            assertEquals(GateStatus.PENDING, reads(agent(), pending.gate.id, asked).gate.status)
            assertEquals(waited, start.elapsedNow(), "asked $asked")
        }
        // Another gate changing wakes nobody here.
        val other = bench.open(bench.pushRequest(sha = SHA_B, diff = "other"))
        val start = testTimeSource.markNow()
        val waiting = async { reads(agent(), pending.gate.id, 1.seconds) }
        runCurrent()
        bench.answer(other, "rev-b")
        assertEquals(GateStatus.PENDING, waiting.await().gate.status)
        assertEquals(1.seconds, start.elapsedNow())
    }

    @Test
    fun nothingWaitsOnADecidedGateAnotherOrganizationsOrANegativeWait() = runTest {
        val bench = GateBench()
        val pending = bench.open(bench.pushRequest())
        val reads = reads(bench)
        val start = testTimeSource.markNow()
        assertEquals(GateStatus.PENDING, reads(agent(), pending.gate.id, (-5).seconds).gate.status)
        assertFailsWith<GateNotFoundException> { reads(user("rev-b", OTHER_ORG), pending.gate.id, 30.seconds) }
        bench.answer(pending, "rev-b")
        assertEquals(GateStatus.APPROVED, reads(agent(), pending.gate.id, 30.seconds).gate.status)
        assertEquals(Duration.ZERO, start.elapsedNow())
    }
}
