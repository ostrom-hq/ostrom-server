// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.orgs.domain.SettleOrgDeletionsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Finishes the deletions of organizations every [interval], on every replica, until the server's
 * scope is cancelled: a deletion reaches the modules (billing ends the subscription) within a
 * minute, and an organization is erased within a minute of its 30 days. A failed pass is logged
 * (counts and error class only) and the next one tries again.
 *
 * @param settle The use case.
 * @param logger Where failures are reported.
 * @param interval The pause between passes.
 * @param passes How many passes to run (unbounded in production; bounded in tests).
 */
class OrgDeletionWorker(
    private val settle: SettleOrgDeletionsUseCase,
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
            val result = settle()
            if (result.failed > 0) {
                val fields =
                    mapOf(
                        "settled" to result.settled,
                        "purged" to result.purged,
                        "failed" to result.failed,
                    )
                logger.warn("organization deletions left for the next pass", fields)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error(
                "organization deletion pass failed",
                mapOf("error" to error::class.simpleName),
            )
        }
    }
}
