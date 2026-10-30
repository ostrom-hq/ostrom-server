// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.infrastructure

import dev.ostrom.server.automations.domain.RunAutomationsUseCase
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The cron scheduler of every replica: a pass every [interval]. Each due automation is claimed by one replica, so
 * any number of replicas run it; a slot is at most [interval] late, well within the two minutes after which the
 * catch-up rule applies.
 *
 * @param runs The passes.
 * @param logger Where a failed pass is reported (its type only).
 * @param interval The pause between two passes.
 */
internal class AutomationScheduler(
    private val runs: RunAutomationsUseCase,
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
            runs.tick()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("automations scheduler pass failed", mapOf("error" to error::class.simpleName))
        }
    }

    private companion object {
        private val DEFAULT_INTERVAL = 20.seconds
    }
}
