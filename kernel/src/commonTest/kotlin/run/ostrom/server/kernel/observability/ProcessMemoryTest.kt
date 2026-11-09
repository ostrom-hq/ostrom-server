// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessMemoryTest {
    @Test
    fun aContainerLimitIsReadFromItsCgroup() {
        assertEquals(268_435_456L, ProcessMemory.parseLimit("268435456\n"), "cgroup v2 memory.max, 256 MiB")
        assertEquals(536_870_912L, ProcessMemory.parseLimit("536870912"), "cgroup v1 memory.limit_in_bytes")
    }

    @Test
    fun noLimitReadsAsNone() {
        assertNull(ProcessMemory.parseLimit("max\n"), "cgroup v2 without a limit")
        assertNull(ProcessMemory.parseLimit("9223372036854771712\n"), "cgroup v1 without a limit")
        assertNull(ProcessMemory.parseLimit(null), "no cgroup (macOS, a plain process)")
        assertNull(ProcessMemory.parseLimit("lots"))
        assertNull(ProcessMemory.parseLimit("0"))
    }

    @Test
    fun theResidentMemoryIsTheOneTheMetricsExport() {
        val bytes = ProcessMemory.residentBytes()
        assertTrue(bytes == null || bytes > 1_000_000, "$bytes")
    }

    @Test
    fun aLimitIsAPositiveNumberOfBytesWhenThereIsOne() {
        val limit = ProcessMemory.limitBytes()
        assertTrue(limit == null || limit > 0, "$limit")
    }
}
