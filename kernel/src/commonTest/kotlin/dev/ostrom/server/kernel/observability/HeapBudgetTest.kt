// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeapBudgetTest {
    @Test
    fun aContainerLimitBoundsTheHeap() {
        val mib = 1024L * 1024
        assertEquals(HeapBudget(targetBytes = 64 * mib, maxBytes = 256 * mib / 100 * 45), HeapBudget.under(256 * mib))
    }

    @Test
    fun withoutALimitTheRuntimeTunesItself() {
        assertNull(HeapBudget.under(null))
    }
}
