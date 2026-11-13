// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlin.uuid.Uuid

/**
 * The live feed of each org's new usage events: what the "live spending map" shows. A subscriber on one replica sees
 * the events every replica records; a slow subscriber loses the oldest events rather than slowing the recorder down.
 */
interface UsageFeed {
    /**
     * Publishes an event to the subscribers of its org, on every replica; never suspends on a subscriber.
     *
     * @param event The event.
     */
    suspend fun publish(event: UsageEvent)

    /**
     * The org's feed.
     *
     * @param orgId The org.
     *
     * @return A hot flow of the org's events published from now on.
     */
    suspend fun subscribe(orgId: Uuid): SharedFlow<UsageEvent>
}
