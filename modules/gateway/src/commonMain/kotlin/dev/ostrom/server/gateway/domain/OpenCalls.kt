// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The model calls this replica relays at once, at most [max]. Each holds memory as long as it runs: its body, a
 * provider connection, a stream to the agent. Held in this replica's memory: with several replicas each bounds its own.
 *
 * @property max The ceiling.
 */
@OptIn(ExperimentalAtomicApi::class)
class OpenCalls(val max: Int) {
    private val count = AtomicInt(0)

    /**
     * Takes a place, without waiting: a call beyond [max] is refused at once.
     *
     * @return True when admitted; the caller then calls [close] exactly once.
     */
    fun tryOpen(): Boolean {
        while (true) {
            val current = count.load()
            if (current >= max) return false
            if (count.compareAndSet(current, current + 1)) return true
        }
    }

    /** Gives back a place taken by [tryOpen]. */
    fun close() {
        count.decrementAndFetch()
    }

    /** The calls open now. */
    val open: Int get() = count.load()
}
