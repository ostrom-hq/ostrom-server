// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.memory.domain.MemoryProposalsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The settling of the agents' proposals (TeamCode's `memory/settle`): every [interval], one replica at a time (the
 * instance's [ScheduledWork] lease), the answered proposals are accepted or rejected, the stale ones rejected and the
 * old rejected ones deleted. An acceptance so shows in the index within about ten seconds.
 *
 * @param proposals The proposals.
 * @param work The lease of the periodic jobs.
 * @param logger Where a failed pass is reported (its type only).
 * @param interval The pause between two passes.
 */
internal class MemorySettleWorker(
    private val proposals: MemoryProposalsService,
    private val work: ScheduledWork,
    private val logger: Logger,
    private val interval: Duration = INTERVAL,
) {
    /** Runs passes until the calling coroutine is cancelled, which the pause between two passes observes. */
    suspend fun run(): Nothing {
        while (true) {
            pass()
            delay(interval)
        }
    }

    /** One pass, if this replica holds the lease; a failure is logged and the next pass tries again. */
    suspend fun pass() {
        try {
            work.runDue(JOB, interval, TIMEOUT) { proposals.settle() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("memory settle pass failed", mapOf("error" to error::class.simpleName))
        }
    }

    private companion object {
        const val JOB = "memory/settle"
        private val INTERVAL: Duration = 10.seconds
        private val TIMEOUT: Duration = 60.seconds
    }
}
