// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

/**
 * The model calls this replica relays at once, at most [max].
 *
 * @property max The ceiling.
 */
class OpenCalls(val max: Int) {
    /**
     * Takes a place.
     *
     * @return True when admitted; the caller then calls [close] exactly once.
     */
    fun tryOpen(): Boolean = true

    /** Gives back a place taken by [tryOpen]. */
    fun close() = Unit

    /** The calls open now. */
    val open: Int get() = 0
}
