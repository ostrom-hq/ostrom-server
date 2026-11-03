// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(NativeRuntimeApi::class)

package dev.ostrom.server.app

import dev.ostrom.server.kernel.observability.HeapBudget
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * Hands [budget] to the Kotlin/Native collector: its target and the most it may grow to, and threads that allocate past
 * the target wait for the collection instead of growing the heap.
 */
internal fun applyHeapBudget(budget: HeapBudget) {
    GC.maxHeapBytes = budget.maxBytes
    GC.targetHeapBytes = minOf(budget.targetBytes, budget.maxBytes)
    GC.pauseOnTargetHeapOverflow = true
}
