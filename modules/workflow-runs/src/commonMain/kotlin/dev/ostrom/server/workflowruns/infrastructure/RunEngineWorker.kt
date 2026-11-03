// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.workflowruns.domain.engine.TickRunsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The engine of every replica: a pass every [interval], at once again while a pass found runs due. Any number of
 * replicas run it; each run is leased to one of them at a time.
 *
 * @param ticks The passes.
 * @param logger Where a failed pass is reported (its type only).
 * @param interval The pause after a pass that found nothing due, or failed.
 */
class RunEngineWorker(
    private val ticks: TickRunsUseCase,
    private val logger: Logger,
    private val interval: Duration = INTERVAL,
) {
    /** Runs passes until the calling coroutine is cancelled, which the pause between two passes observes. */
    suspend fun run(): Nothing {
        while (true) {
            if (pass() == 0) delay(interval)
        }
    }

    /**
     * One pass; a failure is logged and the next pass tries again.
     *
     * @return How many runs it leased: 0 when it failed.
     */
    suspend fun pass(): Int = try {
        ticks.tick()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.error("workflow runs engine pass failed", mapOf("error" to error::class.simpleName))
        0
    }

    private companion object {
        val INTERVAL: Duration = 2.seconds
    }
}
