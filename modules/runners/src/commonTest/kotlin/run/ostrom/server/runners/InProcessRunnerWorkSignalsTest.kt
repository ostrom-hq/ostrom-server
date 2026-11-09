// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.RunnerAssignmentsRepository
import run.ostrom.server.runners.fakes.InMemoryRunnerAssignments
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.infrastructure.InProcessRunnerWorkSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class InProcessRunnerWorkSignalsTest {
    private class RecordingLogger : Logger {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            lines += message to fields
        }
        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private val logger = RecordingLogger()
    private val looked = mutableListOf<Uuid>()

    private fun signals(maxWaiting: Int = 10, work: (Uuid) -> Boolean = { false }) =
        InProcessRunnerWorkSignals(
            object : RunnerAssignmentsRepository by InMemoryRunnerAssignments() {
                override suspend fun hasWork(orgId: Uuid): Boolean {
                    looked += orgId
                    return work(orgId)
                }
            },
            logger,
            maxWaiting = maxWaiting,
        )

    @Test
    fun workArrivingWakesTheHeartbeatsOfItsOrganizationOnly() = runTest {
        val signals = signals()
        val a = async { signals.await(ORG_A) }
        val b = async { signals.await(ORG_B) }
        runCurrent()

        signals.arrived(ORG_A)
        runCurrent()

        assertTrue(a.isCompleted && a.await())
        assertFalse(b.isCompleted)
        b.cancel()
    }

    @Test
    fun beyondItsBoundAHeartbeatIsRefusedAndALeavingOneFreesItsPlace() = runTest {
        val signals = signals(maxWaiting = 2)
        val first = async { signals.await(ORG_A) }
        val second = async { signals.await(ORG_A) }
        runCurrent()

        assertFalse(signals.await(ORG_B), "Bound reached: answered at once")
        second.cancel()
        runCurrent()
        val third = async { signals.await(ORG_B) }
        runCurrent()
        assertFalse(third.isCompleted, "A cancelled wait left its place")

        signals.arrived(ORG_A)
        signals.arrived(ORG_B)
        runCurrent()
        assertTrue(first.await() && third.await())
    }

    @Test
    fun aLookWakesTheWaitingOrganizationsThatHaveWorkAndOnlyThem() = runTest {
        val signals = signals { it == ORG_A }
        signals.look()
        assertEquals(emptyList(), looked, "Nobody waits: no query")

        val a = async { signals.await(ORG_A) }
        val b = async { signals.await(ORG_B) }
        runCurrent()
        signals.look()
        runCurrent()

        assertEquals(setOf(ORG_A, ORG_B), looked.toSet())
        assertTrue(a.isCompleted)
        assertFalse(b.isCompleted)
        b.cancel()
    }

    @Test
    fun aFailedLookIsReportedAndTheOtherOrganizationsAreStillLookedAt() = runTest {
        val signals = signals { if (it == ORG_A) error("database down") else true }
        val a = async { signals.await(ORG_A) }
        val b = async { signals.await(ORG_B) }
        runCurrent()

        signals.look()
        runCurrent()

        val failed = mapOf<String, Any?>("error" to "IllegalStateException")
        assertEquals(listOf("runner work look failed" to failed), logger.lines)
        assertTrue(b.isCompleted)
        assertFalse(a.isCompleted)
        a.cancel()
    }

    @Test
    fun aCancelledLookIsNeverSwallowed() = runTest {
        val signals = signals { throw CancellationException("stopping") }
        val a = async { signals.await(ORG_A) }
        runCurrent()

        assertFailsWith<CancellationException> { signals.look() }
        assertEquals(emptyList(), logger.lines)
        a.cancel()
    }

    @Test
    fun theWatchLooksEverySecond() = runTest {
        val signals = signals { true }
        val watch = launch { signals.watch() }
        val a = async { signals.await(ORG_A) }
        runCurrent()

        advanceTimeBy(999)
        runCurrent()
        assertFalse(a.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertTrue(a.isCompleted)
        assertEquals(listOf(ORG_A), looked)
        watch.cancel()
    }
}
