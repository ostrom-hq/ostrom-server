// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import kotlinx.coroutines.CancellationException
import run.ostrom.server.events.domain.EventRules
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.kernel.database.EveryCallScheduledWork
import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.database.repeatDue
import run.ostrom.server.kernel.logging.Logger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Deletes the events older than [EventRules.RETENTION], a batch a minute, on one replica at a time (TeamCode's
 * `team-events/purge`, under its lease: [ScheduledWork]).
 *
 * @param events The journal.
 * @param clock The time.
 * @param logger Where a failed pass is reported (its type only).
 * @param interval The pause between two passes.
 */
class EventsPurgeWorker(
    private val events: OrgEventsRepository,
    private val clock: Clock,
    private val logger: Logger,
    private val interval: Duration = 1.minutes,
) {
    /**
     * Runs passes until the calling coroutine is cancelled.
     *
     * @param work The lease of the job, `events/purge`.
     */
    suspend fun run(work: ScheduledWork = EveryCallScheduledWork): Nothing =
        work.repeatDue("events/purge", interval, PASS_TIMEOUT, logger, "events purge failed") { pass() }

    /** One pass: a batch of old events deleted; a failure is logged and the next pass tries again. */
    suspend fun pass() {
        try {
            events.purge(clock.now() - EventRules.RETENTION, BATCH)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("events purge failed", mapOf("error" to error::class.simpleName))
        }
    }

    private companion object {
        const val BATCH = 1_000

        /** The longest a pass may take. */
        val PASS_TIMEOUT: Duration = 45.seconds
    }
}
