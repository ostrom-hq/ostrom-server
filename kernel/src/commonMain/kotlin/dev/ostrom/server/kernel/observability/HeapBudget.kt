// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.observability

/**
 * How far the server lets its Kotlin heap grow under a container memory limit, so that a burst of requests waits for
 * the garbage collector instead of growing the heap past the limit (chart-load 41632955665: replicas holding 26 streams
 * each were OOMKilled within a second of a burst of 100, from 45 to 145 MiB at rest).
 *
 * @property targetBytes Where a collection starts.
 * @property maxBytes The most the collector lets its target grow to.
 */
data class HeapBudget(val targetBytes: Long, val maxBytes: Long) {
    /** The budget a limit gives. */
    companion object {
        /**
         * A quarter of [limitBytes] to start collecting, 45 % at most; the rest is the binary, the threads, native
         * buffers and what the allocator keeps.
         *
         * @param limitBytes The container's memory limit, null without one (no budget then: the runtime's own tuning).
         */
        fun under(limitBytes: Long?): HeapBudget? =
            limitBytes?.let { HeapBudget(targetBytes = it / TARGET_SHARE, maxBytes = it / WHOLE * MAX_PERCENT) }

        private const val TARGET_SHARE = 4L
        private const val MAX_PERCENT = 45L
        private const val WHOLE = 100L
    }
}
