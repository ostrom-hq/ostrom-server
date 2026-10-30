// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.identity.domain.RateLimiter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * [RateLimiter] with fixed windows kept in memory. Each replica counts on its own, so the real limit is the configured
 * one times the number of replicas: enough against guessing and floods, and no database write per attempt. Memory is
 * bounded: past [maxKeys], the expired windows are dropped, then the oldest ones.
 *
 * @param clock The clock.
 * @param maxKeys The most keys kept at once.
 */
class InMemoryRateLimiter(
    private val clock: Clock,
    private val maxKeys: Int = 100_000,
) : RateLimiter {
    private class Window(val start: Instant, var count: Int)

    private val mutex = Mutex()
    private val windows = LinkedHashMap<String, Window>()

    override suspend fun tryAcquire(key: String, limit: Int, window: Duration): Boolean = mutex.withLock {
        val now = clock.now()
        val current = windows[key]?.takeIf { now - it.start < window } ?: Window(now, 0).also {
            windows.remove(key)
            if (windows.size >= maxKeys) evict(now, window)
            windows[key] = it
        }
        current.count++
        current.count <= limit
    }

    private fun evict(now: Instant, window: Duration) {
        windows.entries.removeAll { now - it.value.start >= window }
        while (windows.size >= maxKeys) windows.remove(windows.keys.first())
    }
}
