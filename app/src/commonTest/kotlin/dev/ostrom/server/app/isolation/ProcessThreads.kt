// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app.isolation

/** The name of each thread of this process (`/proc/self/task/<tid>/comm`); null where there is no `/proc`. */
internal expect fun processThreadNames(): List<String>?

/**
 * One line telling how many threads the process has, and how many carry each name, most first (server#266 counts the
 * same names on the real server).
 *
 * @param label What the line follows, for instance `threads after seeded round 2`.
 */
internal fun threadsLine(label: String): String {
    val names = processThreadNames() ?: return "$label: no /proc to read threads"
    val groups = names.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    return "$label: ${names.size} threads (${groups.joinToString { "${it.key} x${it.value}" }})"
}
