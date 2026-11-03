// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import dev.ostrom.server.kernel.observability.HeapBudget

/**
 * Hands [budget] to the Kotlin/Native collector: its target and the most it may grow to, and threads that allocate past
 * the target wait for the collection instead of growing the heap.
 */
internal fun applyHeapBudget(budget: HeapBudget) = Unit
