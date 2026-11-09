// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.infrastructure

import run.ostrom.server.governance.domain.ExpireConfirmationsUseCase
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Expires the confirmations left unanswered every [interval], on every replica, until the server's scope is cancelled:
 * each expiry has its audit event within a minute, even when nobody reads the confirmation again. A failed pass is
 * logged (error class only) and the next one tries again.
 *
 * @param expire The use case.
 * @param logger Where failures are reported.
 * @param interval The pause between passes.
 * @param passes How many passes to run (unbounded in production; bounded in tests).
 */
class ConfirmationSweeper(
    private val expire: ExpireConfirmationsUseCase,
    private val logger: Logger,
    private val interval: Duration = 1.minutes,
    private val passes: Long = Long.MAX_VALUE,
) {
    /** Runs the passes. */
    suspend fun run() {
        var done = 0L
        while (done < passes) {
            pass()
            done++
            if (done < passes) delay(interval)
        }
    }

    private suspend fun pass() {
        try {
            expire.everywhere()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("governance confirmation sweep failed", mapOf("error" to error::class.simpleName))
        }
    }
}
