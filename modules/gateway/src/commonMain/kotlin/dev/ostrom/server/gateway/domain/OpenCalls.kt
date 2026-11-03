// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import dev.ostrom.server.kernel.observability.ProcessMemory
import kotlinx.coroutines.sync.Semaphore

/**
 * The model calls this replica relays at once, at most [max]. Each holds memory as long as it runs: its body, a
 * provider connection, a stream to the agent. Held in this replica's memory: with several replicas each bounds its own.
 *
 * @property max The ceiling.
 * @property memoryCeilingBytes Past this resident memory no call is admitted, whatever the count; null for none.
 * @property residentBytes Reads the replica's resident memory; null when the system does not say.
 */
class OpenCalls(
    val max: Int,
    val memoryCeilingBytes: Long? = null,
    private val residentBytes: () -> Long? = ProcessMemory::residentBytes,
) {
    private val places = Semaphore(max)

    /**
     * Takes a place, without waiting: a call beyond [max], or once the replica's resident memory reaches
     * [memoryCeilingBytes], is refused at once.
     *
     * @return True when admitted; the caller then calls [close] exactly once.
     */
    fun tryOpen(): Boolean = !pastMemoryCeiling() && places.tryAcquire()

    /** An unreadable memory, or no ceiling, leaves the count alone to decide. */
    private fun pastMemoryCeiling(): Boolean =
        memoryCeilingBytes?.let { ceiling -> residentBytes()?.let { it >= ceiling } } == true

    /**
     * Gives back a place taken by [tryOpen].
     *
     * @throws IllegalStateException When no place is taken: a second close never raises the ceiling.
     */
    fun close() = places.release()

    /** The calls open now. */
    val open: Int get() = max - places.availablePermits
}
