// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.memory.domain.MemoryCurationServiceImpl
import run.ostrom.server.memory.domain.MemoryProposalsServiceImpl
import run.ostrom.server.memory.fakes.FakeCurationRuns
import run.ostrom.server.memory.fakes.FakeCurationStore
import run.ostrom.server.memory.fakes.FakeProposalStore
import run.ostrom.server.memory.fakes.FakeProposalWaits
import run.ostrom.server.memory.fakes.FakeRepository
import run.ostrom.server.memory.fakes.ORG_A
import run.ostrom.server.memory.fakes.RecordingAudit
import run.ostrom.server.memory.infrastructure.MemorySchedule
import run.ostrom.server.memory.infrastructure.MemoryWorker
import run.ostrom.server.memory.infrastructure.memoryWorkers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The memory's periodic jobs on the instance's lease: the settling every ten seconds, the curation hourly. */
class MemoryWorkerTest {
    private val logged = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val logger = object : Logger {
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            logged += message to fields
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    /** The lease: it runs the job unless another replica [held] it, and records what it was asked. */
    private class Lease(var held: Boolean = false) : ScheduledWork {
        val asked = mutableListOf<Triple<String, Duration, Duration>>()

        override suspend fun runDue(name: String, interval: Duration, timeout: Duration, block: suspend () -> Unit) =
            (!held).also {
                asked += Triple(name, interval, timeout)
                if (it) block()
            }
    }

    @Test
    fun aJobAsksTheLeaseEveryTickUntilCancelled() = runTest {
        var count = 0
        val lease = Lease()
        val job = launch { MemoryWorker(MemorySchedule.SETTLE, lease, logger) { count++ }.run() }
        advanceTimeBy(21.seconds)
        job.cancel()
        assertEquals(3, count)
        assertEquals(Triple("memory/settle", 10.seconds, 60.seconds), lease.asked.first())
        lease.held = true
        MemoryWorker(MemorySchedule.SETTLE, lease, logger) { count++ }.pass()
        assertEquals(3, count, "another replica holds it")
        val curate = MemorySchedule.CURATE
        assertEquals(
            listOf("memory/curate", 1.minutes, 1.hours, 240.seconds),
            listOf(curate.job, curate.tick, curate.interval, curate.timeout),
        )
    }

    @Test
    fun aFailedPassIsLoggedByItsTypeOnlyAndACancellationIsNeverSwallowed() = runTest {
        MemoryWorker(MemorySchedule.CURATE, Lease(), logger) { throw IllegalStateException("secret") }.pass()
        val fields = mapOf<String, Any?>("job" to "memory/curate", "error" to "IllegalStateException")
        val failed = "memory pass failed" to fields
        assertEquals(listOf(failed), logged)
        assertFailsWith<CancellationException> {
            MemoryWorker(MemorySchedule.SETTLE, Lease(), logger) { throw CancellationException("stop") }.pass()
        }
    }

    @Test
    fun theMemoryRunsTheSettlingAndTheCurationEachUnderItsOwnJob() = runTest {
        val repository = FakeRepository()
        val audit = RecordingAudit()
        val store = FakeProposalStore(repository)
        val proposals = MemoryProposalsServiceImpl(repository, store, FakeProposalWaits(), audit, Clock.System)
        val runs = FakeCurationRuns().apply { add(ORG_A) }
        val curation = MemoryCurationServiceImpl(FakeCurationStore(), runs, audit, Clock.System)
        val lease = Lease()
        memoryWorkers(lease, logger, proposals, curation).forEach { it.pass() }
        assertEquals(listOf("memory/settle", "memory/curate"), lease.asked.map { it.first })
        assertEquals(listOf(ORG_A), runs.retained, "the curation ran its retention")
        assertEquals(emptyList(), logged)
    }
}
