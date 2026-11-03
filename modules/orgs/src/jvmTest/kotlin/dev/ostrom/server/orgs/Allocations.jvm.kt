// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

internal actual fun allocatedBytes(): Long? =
    threads.getThreadAllocatedBytes(threads.allThreadIds).filter { it > 0 }.sum()

internal actual fun resetHeapPeak() =
    ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.forEach { it.resetPeakUsage() }

internal actual fun heapPeakBytes(): Long? =
    ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used }
