// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.observability

import java.lang.management.ManagementFactory

// The JVM target only runs the tests (never shipped): the JVM's own figures stand in for the process'.

private const val NANOS_PER_SECOND = 1_000_000_000.0

/** CPU time of the JVM process, from its operating system bean. */
internal actual fun processCpuSeconds(): Double =
    (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime /
        NANOS_PER_SECOND

/** The heap in use: the JVM does not expose its resident set. */
internal actual fun processResidentBytes(): Long? = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

/** The tests run outside any container limit that matters: none. */
internal actual fun processMemoryLimit(): String? = null
