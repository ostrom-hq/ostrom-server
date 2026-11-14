// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Relay.kt; see NOTICE.
package run.ostrom.server.push

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import run.ostrom.core.api.models.PushRelay

/** The signatures of the requests sent within the skew window, so a copied request is not sent twice. */
internal class ReplayGuard(private val maxEntries: Int = DEFAULT_MAX_KEYS) {
    private val mutex = Mutex()
    private val seen = HashMap<String, Long>()

    /** True the first time [signature] is seen; false for a copy, or when the guard is full of live entries. */
    suspend fun first(signature: String, timestamp: Long, now: Long): Boolean = mutex.withLock {
        if (seen.size >= maxEntries) seen.entries.removeAll { now - it.value > 2 * PushRelay.MAX_SKEW_SECONDS }
        seen.size < maxEntries && seen.put(signature, timestamp) == null
    }
}
