// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import org.koin.core.Koin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs the periodic job [name] on one replica at a time ([ScheduledWork.runDue]) until the calling coroutine is
 * cancelled: after a run here, it waits [interval]; while the job is not due or another replica holds it, it asks
 * again every [poll]. A run that fails or overruns its [timeout] is logged as [failure] (the error's type only, never
 * its message: PostgreSQL quotes row values in it), and the next one runs at its time: a periodic job never stops the
 * server.
 *
 * @param name The job (`mcp/retain`, `usage/purge`).
 * @param interval The time between two runs, across every replica.
 * @param timeout The longest a run may take (at most 5 minutes).
 * @param logger Where a failed run is reported.
 * @param failure The warning a failed run logs.
 * @param poll How often to ask while the job is not due here: the interval, at most a minute.
 * @param block The work.
 */
suspend fun ScheduledWork.repeatDue(
    name: String,
    interval: Duration,
    timeout: Duration,
    logger: Logger,
    failure: String,
    poll: Duration = minOf(interval, 1.minutes),
    block: suspend () -> Unit,
): Nothing {
    while (true) {
        val ran = try {
            runDue(name, interval, timeout, block)
        } catch (error: TimeoutCancellationException) {
            // The run's own timeout, not this coroutine's cancellation: the lease is given back, the next one runs.
            logger.warn(failure, mapOf("job" to name, "error" to error::class.simpleName))
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn(failure, mapOf("job" to name, "error" to error::class.simpleName))
            true
        }
        delay(if (ran) interval else poll)
    }
}

/**
 * The [ScheduledWork] of this server: the instance module's lease on PostgreSQL, or, in a server built without it (a
 * module's own tests), [EveryCallScheduledWork].
 */
fun Koin.scheduledWork(): ScheduledWork = getOrNull<ScheduledWork>() ?: EveryCallScheduledWork

/**
 * Runs every job at each call, with no lease: what a single process does when no instance module coordinates its
 * replicas. Its interval and timeout are those of [repeatDue], which waits the interval after each run.
 */
object EveryCallScheduledWork : ScheduledWork {
    override suspend fun runDue(
        name: String,
        interval: Duration,
        timeout: Duration,
        block: suspend () -> Unit,
    ): Boolean {
        block()
        return true
    }
}
