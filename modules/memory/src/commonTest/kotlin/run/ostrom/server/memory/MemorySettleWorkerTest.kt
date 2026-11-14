// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryProposalsService
import run.ostrom.server.memory.domain.NewMemory
import run.ostrom.server.memory.infrastructure.MemorySettleWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The settling pass, every ten seconds on the instance's lease, its failures logged by type. */
class MemorySettleWorkerTest {
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

    private class Settle(var failure: Throwable? = null) : MemoryProposalsService {
        var count = 0

        override suspend fun settle(): Int {
            count++
            failure?.let { throw it }
            return 0
        }

        override suspend fun propose(caller: MemoryCaller, entry: NewMemory, sessionId: String?): MemoryEntry =
            error("unused")
        override suspend fun proposals(caller: MemoryCaller): List<MemoryEntry> = error("unused")
        override suspend fun decide(caller: MemoryCaller, id: Uuid, accept: Boolean): MemoryEntry = error("unused")
    }

    @Test
    fun aPassSettlesOnTheLeaseEveryTenSecondsUntilCancelled() = runTest {
        val settle = Settle()
        val lease = Lease()
        val job = launch { MemorySettleWorker(settle, lease, logger).run() }
        advanceTimeBy(21.seconds)
        job.cancel()
        assertEquals(3, settle.count)
        assertEquals(Triple("memory/settle", 10.seconds, 60.seconds), lease.asked.first())
        lease.held = true
        MemorySettleWorker(settle, lease, logger).pass()
        assertEquals(3, settle.count, "another replica holds it")
    }

    @Test
    fun aFailedPassIsLoggedByItsTypeOnlyAndACancellationIsNeverSwallowed() = runTest {
        MemorySettleWorker(Settle(IllegalStateException("secret")), Lease(), logger).pass()
        val failed = "memory settle pass failed" to mapOf<String, Any?>("error" to "IllegalStateException")
        assertEquals(listOf(failed), logged)
        assertFailsWith<CancellationException> {
            MemorySettleWorker(Settle(CancellationException("stop")), Lease(), logger).pass()
        }
    }
}
