// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageFeed
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * [UsageFeed] within this replica: one [MutableSharedFlow] per org that has had a subscriber here. An org nobody
 * watches costs one map lookup per event; its flow is created by the first subscriber and kept (one per org, bounded
 * by the orgs). [ReplicatedUsageFeed] carries the events to the other replicas' feeds.
 *
 * @param buffer Events kept per org for a slow subscriber before the oldest are dropped.
 */
class UsageFeedImpl(private val buffer: Int = 256) : UsageFeed {
    private val lock = Mutex()
    private val flows = HashMap<Uuid, MutableSharedFlow<UsageEvent>>()

    override suspend fun publish(event: UsageEvent) {
        lock.withLock { flows[event.orgId] }?.tryEmit(event)
    }

    override suspend fun subscribe(orgId: Uuid): SharedFlow<UsageEvent> = lock.withLock {
        flows.getOrPut(orgId) {
            MutableSharedFlow(extraBufferCapacity = buffer, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }
    }.asSharedFlow()

    /**
     * Whether someone follows the org's feed on this replica now.
     *
     * @param orgId The org.
     *
     * @return True while it has a subscriber here.
     */
    suspend fun watched(orgId: Uuid): Boolean {
        val flow = lock.withLock { flows[orgId] } ?: return false
        return flow.subscriptionCount.value > 0
    }
}
