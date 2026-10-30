// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.infrastructure

import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageFeed
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * [UsageFeed] in memory: one [MutableSharedFlow] per org that has had a subscriber. An org nobody watches costs one
 * map lookup per event; its flow is created by the first subscriber and kept (one per org, bounded by the orgs).
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
        flows.getOrPut(orgId) { MutableSharedFlow(extraBufferCapacity = buffer, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    }.asSharedFlow()
}
