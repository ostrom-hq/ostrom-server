// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.memory.domain.MemoryCurationService
import run.ostrom.server.memory.domain.MemoryProposalsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * One of the memory's periodic jobs on the instance's [ScheduledWork] lease, one replica at a time: every [tick] this
 * replica asks the lease, which runs [block] when the job is due ([schedule]'s interval since the end of the last run).
 *
 * - `memory/settle` (TeamCode's): every 10 seconds, the answered proposals are accepted or rejected, the stale ones
 *   rejected and the old rejected ones deleted, so an acceptance shows in the index within about ten seconds.
 * - `memory/curate` (TeamCode's): hourly, the curation of the organizations that turned it on, and every
 *   organization's retention.
 *
 * @param schedule The job's name and times.
 * @param work The lease of the periodic jobs.
 * @param logger Where a failed pass is reported (its type only).
 * @param block The work.
 */
internal class MemoryWorker(
    private val schedule: MemorySchedule,
    private val work: ScheduledWork,
    private val logger: Logger,
    private val block: suspend () -> Unit,
) {
    /** Runs passes until the calling coroutine is cancelled, which the pause between two asks observes. */
    suspend fun run(): Nothing {
        while (true) {
            pass()
            delay(schedule.tick)
        }
    }

    /** One pass, if it is due and this replica holds the lease; a failure is logged and a later pass tries again. */
    suspend fun pass() {
        try {
            work.runDue(schedule.job, schedule.interval, schedule.timeout) { block() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("memory pass failed", mapOf("job" to schedule.job, "error" to error::class.simpleName))
        }
    }
}

/** The memory's two jobs: the settling of [proposals] and the curation, each run with a new run id. */
internal fun memoryWorkers(
    work: ScheduledWork,
    logger: Logger,
    proposals: MemoryProposalsService,
    curation: MemoryCurationService,
): List<MemoryWorker> = listOf(
    MemoryWorker(MemorySchedule.SETTLE, work, logger) { proposals.settle() },
    MemoryWorker(MemorySchedule.CURATE, work, logger) { curation.run(Uuid.random().toString()) },
)

/**
 * A periodic job's name and times.
 *
 * @property tick How often a replica asks whether it is due.
 * @property interval The time between the end of a run and the start of the next.
 * @property timeout The longest a run may take.
 */
internal enum class MemorySchedule(val job: String, val tick: Duration, val interval: Duration, val timeout: Duration) {
    SETTLE("memory/settle", SETTLE_EVERY, SETTLE_EVERY, SETTLE_TIMEOUT),
    CURATE("memory/curate", CURATE_TICK, CURATE_EVERY, CURATE_TIMEOUT),
}

private val SETTLE_EVERY: Duration = 10.seconds
private val SETTLE_TIMEOUT: Duration = 60.seconds
private val CURATE_TICK: Duration = 1.minutes
private val CURATE_EVERY: Duration = 1.hours
private val CURATE_TIMEOUT: Duration = 240.seconds
