// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Relay.kt; see NOTICE.
package run.ostrom.server.push

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The most keys a window holds before the minutes already over are dropped. */
internal const val DEFAULT_MAX_KEYS: Int = 200_000

/**
 * Fixed one-minute windows for the three quotas, checked together: a request counts only when all of them accept it.
 * Bounded: when a window holds too many keys, the minutes already over are dropped; still full, new keys are refused.
 */
internal class Quotas(private val limits: RelayLimits, maxKeys: Int = DEFAULT_MAX_KEYS) {
    private val mutex = Mutex()
    private val instances = Window(maxKeys)
    private val tokens = Window(maxKeys)
    private var totalMinute = 0L
    private var totalCount = 0

    /** Counts one request of [instance] for [token] in [minute], or answers false when a quota refuses it. */
    suspend fun take(instance: String, token: String, minute: Long): Boolean = mutex.withLock {
        if (totalMinute != minute) {
            totalMinute = minute
            totalCount = 0
        }
        val accepted = totalCount < limits.total &&
            instances.hasRoom(instance, minute) && tokens.hasRoom(token, minute) &&
            instances.count(instance, minute) < limits.perInstance && tokens.count(token, minute) < limits.perToken
        if (accepted) {
            instances.add(instance, minute)
            tokens.add(token, minute)
            totalCount++
        }
        accepted
    }

    /** The counts of one quota, per key, for the minute they were last written. */
    private class Window(private val maxKeys: Int) {
        private val counts = HashMap<String, Pair<Long, Int>>()

        fun count(key: String, minute: Long): Int = counts[key]?.takeIf { it.first == minute }?.second ?: 0

        fun hasRoom(key: String, minute: Long): Boolean {
            if (counts.size >= maxKeys) counts.entries.removeAll { it.value.first != minute }
            return counts.size < maxKeys || key in counts
        }

        fun add(key: String, minute: Long) {
            counts[key] = minute to count(key, minute) + 1
        }
    }
}
