// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.infrastructure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import run.ostrom.server.events.domain.EventRules
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.kernel.logging.Logger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Deletes the events older than [EventRules.RETENTION] on every replica, a batch a minute (TeamCode's
 * `team-events/purge`): each batch is one `DELETE`, so replicas purging together only share the work.
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
    /** Runs passes until the calling coroutine is cancelled. */
    suspend fun run(): Nothing {
        while (true) {
            pass()
            delay(interval)
        }
    }

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
    }
}
