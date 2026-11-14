// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.ProcessOutboxUseCase
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Sends the queued Stripe tasks every [interval], until the server's scope is cancelled. A failed pass is logged and
 * the next one retries: the worker never dies of a database or network error.
 *
 * @param process The outbox use case.
 * @param logger Where failed passes are logged.
 * @param interval The pause between passes.
 * @param batch The tasks of one pass.
 * @param passes How many passes to run (unbounded in production; bounded in tests).
 * @param enabled Whether billing is on now: a pass while it is off sends nothing and the tasks wait.
 */
class OutboxWorker(
    private val process: ProcessOutboxUseCase,
    private val logger: Logger,
    private val interval: Duration = 10.seconds,
    private val batch: Int = 100,
    private val passes: Long = Long.MAX_VALUE,
    private val enabled: suspend () -> Boolean = { true },
) {
    /** Runs the passes. */
    suspend fun run() {
        var done = 0L
        while (done < passes) {
            try {
                if (enabled()) process(batch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error("billing outbox pass failed", mapOf("error" to error::class.simpleName), error)
            }
            done++
            if (done < passes) delay(interval)
        }
    }
}
