// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * At most [perMinute] calls a minute to one event address, as an automation's webhook (TeamCode's `EventRateLimit`).
 * Counted in memory, per replica, for at most [maxKeys] addresses: a flood of unknown wait ids costs no write and no
 * row.
 *
 * @param clock The time.
 * @param perMinute The most calls a minute to one address.
 * @param maxKeys The most addresses counted at once.
 */
class WaitEventRateLimit(
    private val clock: Clock,
    private val perMinute: Int = PER_MINUTE,
    private val maxKeys: Int = MAX_KEYS,
) {
    private class Window(val start: Instant, var count: Int)

    private val windows = mutableMapOf<String, Window>()
    private val lock = Mutex()

    /**
     * Counts one call to [key].
     *
     * @throws WaitEventRateLimitException Past [perMinute] calls in the minute of its first.
     */
    suspend fun check(key: String) {
        val count = lock.withLock {
            val now = clock.now()
            if (windows.size >= maxKeys && key !in windows) {
                windows.entries.removeAll { now - it.value.start >= WINDOW }
                if (windows.size >= maxKeys) windows.clear()
            }
            val window = windows[key]?.takeIf { now - it.start < WINDOW } ?: Window(now, 0).also { windows[key] = it }
            ++window.count
        }
        if (count > perMinute) throw WaitEventRateLimitException(perMinute)
    }

    private companion object {
        const val PER_MINUTE = 30
        const val MAX_KEYS = 10_000
    }
}

/** How long a count lasts. */
private val WINDOW = 1.minutes
