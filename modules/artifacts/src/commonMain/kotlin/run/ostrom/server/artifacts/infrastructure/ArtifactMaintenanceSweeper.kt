// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs the maintenance of artifacts every [interval], on every replica, until the server's scope is cancelled (two
 * replicas on the same rows are safe: each end and deletion is conditional). A failed pass is logged (error class
 * only) and the next one tries again.
 *
 * @param pass One pass of the maintenance.
 * @param logger Where failures are reported.
 * @param interval The pause between passes.
 * @param passes How many passes to run (unbounded in production; bounded in tests).
 */
internal class ArtifactMaintenanceSweeper(
    private val pass: suspend () -> Unit,
    private val logger: Logger,
    private val interval: Duration = 1.minutes,
    private val passes: Long = Long.MAX_VALUE,
) {
    /** Runs the passes. */
    suspend fun run() {
        var done = 0L
        while (done < passes) {
            try {
                pass()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.error("artifacts maintenance failed", mapOf("error" to error::class.simpleName))
            }
            done++
            if (done < passes) delay(interval)
        }
    }
}
