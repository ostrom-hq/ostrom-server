// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.ResolveDueWaitsUseCase
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The clock of the waits that resolve by themselves, on every replica: a pass every [interval] (5 s, TeamCode's
 * `waits/due`). Each wait is resolved by one conditional update, so any number of replicas pass together.
 *
 * @param waits The passes.
 * @param logger Where a failed pass is reported (its type only).
 * @param interval The pause between two passes.
 */
internal class DueWaitsWorker(
    private val waits: ResolveDueWaitsUseCase,
    private val logger: Logger,
    private val interval: Duration = DEFAULT_INTERVAL,
) {
    /** Runs passes until the calling coroutine is cancelled, which the pause between two passes observes. */
    suspend fun run(): Nothing {
        while (true) {
            pass()
            delay(interval)
        }
    }

    /** One pass; a failure is logged and the next pass tries again. */
    suspend fun pass() {
        try {
            waits.pass()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("due waits pass failed", mapOf("error" to error::class.simpleName))
        }
    }

    private companion object {
        private val DEFAULT_INTERVAL = 5.seconds
    }
}
