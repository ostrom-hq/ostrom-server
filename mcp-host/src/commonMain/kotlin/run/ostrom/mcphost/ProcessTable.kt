// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

/** What the host reads and does about processes it did not start itself: the descendants of its children. */
interface ProcessTable {
    /**
     * Every live descendant of a process.
     *
     * @param pid The process.
     */
    fun descendants(pid: Long): List<Long>

    /**
     * Whether a process lives.
     *
     * @param pid The process.
     */
    fun alive(pid: Long): Boolean

    /**
     * Signals one process.
     *
     * @param pid The process.
     * @param force SIGKILL instead of SIGTERM.
     */
    fun signal(pid: Long, force: Boolean)

    /**
     * The resident memory of processes, summed.
     *
     * @param pids The processes.
     * @return The bytes, or null when the platform gives no reading.
     */
    fun rssBytes(pids: List<Long>): Long?
}
