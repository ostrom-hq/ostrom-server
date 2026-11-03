// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import kotlinx.coroutines.sync.Semaphore

/**
 * The model calls this replica relays at once, at most [max]. Each holds memory as long as it runs: its body, a
 * provider connection, a stream to the agent. Held in this replica's memory: with several replicas each bounds its own.
 *
 * @property max The ceiling.
 */
class OpenCalls(val max: Int) {
    private val places = Semaphore(max)

    /**
     * Takes a place, without waiting: a call beyond [max] is refused at once.
     *
     * @return True when admitted; the caller then calls [close] exactly once.
     */
    fun tryOpen(): Boolean = places.tryAcquire()

    /**
     * Gives back a place taken by [tryOpen].
     *
     * @throws IllegalStateException When no place is taken: a second close never raises the ceiling.
     */
    fun close() = places.release()

    /** The calls open now. */
    val open: Int get() = max - places.availablePermits
}
