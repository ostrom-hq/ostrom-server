// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(NativeRuntimeApi::class)

package dev.ostrom.server.app

import dev.ostrom.server.kernel.observability.HeapBudget
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeapTest {
    @Test
    fun theCollectorTakesTheBudget() {
        val target = GC.targetHeapBytes
        val max = GC.maxHeapBytes
        val pause = GC.pauseOnTargetHeapOverflow
        try {
            applyHeapBudget(HeapBudget(targetBytes = 512L shl 20, maxBytes = 1L shl 30))
            assertEquals(1L shl 30, GC.maxHeapBytes)
            assertTrue(GC.targetHeapBytes <= 1L shl 30, "${GC.targetHeapBytes}")
            assertTrue(GC.pauseOnTargetHeapOverflow, "A burst waits for the collector")
        } finally {
            GC.pauseOnTargetHeapOverflow = pause
            GC.maxHeapBytes = max
            GC.targetHeapBytes = target
        }
    }
}
