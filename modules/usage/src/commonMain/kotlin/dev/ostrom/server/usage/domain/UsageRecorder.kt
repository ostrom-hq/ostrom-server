// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.domain

import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException

/**
 * Records one usage event: written to the ledger and published on the org's live feed. Bound in Koin by the usage
 * module; the gateway calls it after each model call, so it never throws for a storage failure.
 */
interface UsageRecorder {
    /**
     * Records it. A failed write is logged and the event is still published live; only a cancellation comes out.
     *
     * @param event The event.
     */
    suspend fun record(event: UsageEvent)
}

/**
 * [UsageRecorder] on the repository and the feed.
 *
 * @param repository The ledger.
 * @param feed The live feed.
 * @param logger Where a failed write is reported.
 */
class UsageRecorderImpl(
    private val repository: UsageRepository,
    private val feed: UsageFeed,
    private val logger: Logger,
) : UsageRecorder {
    override suspend fun record(event: UsageEvent) {
        try {
            repository.insert(event.orgId, listOf(event))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The model call already happened: losing one ledger line is better than failing the caller's answer.
            // The class only, never the message: PostgreSQL quotes row values in it.
            val fields = mapOf("org_id" to event.orgId, "kind" to event.kind.name, "error" to error::class.simpleName)
            logger.warn("usage event not recorded", fields)
        }
        feed.publish(event)
    }
}
