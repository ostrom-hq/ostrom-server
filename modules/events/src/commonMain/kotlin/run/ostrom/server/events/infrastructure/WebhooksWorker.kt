// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.infrastructure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import run.ostrom.server.events.domain.DeliverWebhooksUseCase
import run.ostrom.server.kernel.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The webhooks' worker on every replica (TeamCode's `webhooks/deliver` and `webhooks/maintain` jobs): a delivery
 * pass every second, the maintenance every minute. Replicas share the work through the deliveries' leases.
 *
 * @param deliver The passes.
 * @param logger Where a failed pass is reported (its type only).
 * @param interval The pause between two delivery passes.
 * @param maintainEvery The delivery passes between two maintenances.
 */
class WebhooksWorker(
    private val deliver: DeliverWebhooksUseCase,
    private val logger: Logger,
    private val interval: Duration = 1.seconds,
    private val maintainEvery: Int = MAINTAIN_EVERY,
) {
    /** Runs passes until the calling coroutine is cancelled. */
    suspend fun run(): Nothing {
        var passes = 0
        while (true) {
            if (passes % maintainEvery == 0) guarded("webhooks maintenance failed") { deliver.maintain() }
            guarded("webhooks delivery failed") { deliver() }
            passes = (passes + 1) % maintainEvery
            delay(interval)
        }
    }

    private suspend fun guarded(message: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error(message, mapOf("error" to error::class.simpleName))
        }
    }

    private companion object {
        const val MAINTAIN_EVERY = 60
    }
}
