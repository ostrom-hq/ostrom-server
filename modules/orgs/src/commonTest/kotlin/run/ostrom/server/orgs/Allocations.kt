// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs

/** The bytes every live thread of the process has allocated so far, where the platform counts them (the JVM). */
internal expect fun allocatedBytes(): Long?

/** Starts a new heap peak, where the platform keeps one (the JVM). */
internal expect fun resetHeapPeak()

/** The heap's peak since [resetHeapPeak], where the platform keeps one (the JVM). */
internal expect fun heapPeakBytes(): Long?
