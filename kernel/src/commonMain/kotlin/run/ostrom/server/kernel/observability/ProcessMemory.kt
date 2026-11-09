// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.observability

/**
 * The server's resident memory and the memory limit of its container, read when asked: what a module that holds memory
 * per request (the gateway's open model calls) weighs before admitting one more.
 */
object ProcessMemory {
    /** Resident bytes of this process; null where the system does not say. */
    fun residentBytes(): Long? = processResidentBytes()

    /** The container's memory limit in bytes (cgroup v2, else v1); null without a limit or a cgroup. */
    fun limitBytes(): Long? = parseLimit(processMemoryLimit())

    /**
     * A cgroup memory limit: `max` (v2) or a value of 2^60 bytes and more (v1's "no limit") is none.
     *
     * @param text The first line of `memory.max` or `memory.limit_in_bytes`, or null when neither is readable.
     */
    internal fun parseLimit(text: String?): Long? =
        text?.let { it.trim().toLongOrNull() }?.takeIf { it in 1 until NO_LIMIT }

    /** cgroup v1 writes "no limit" as the largest page-aligned 63-bit value; 2^60 bytes is past any real limit. */
    private const val NO_LIMIT = 1L shl 60
}

/** The first line of the cgroup memory limit file, or null where there is none. */
internal expect fun processMemoryLimit(): String?
