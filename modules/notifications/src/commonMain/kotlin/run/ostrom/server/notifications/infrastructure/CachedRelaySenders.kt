// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/push/CachedPushSenders.kt; see NOTICE.
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.notifications.domain.FcmSender
import run.ostrom.server.notifications.domain.RelaySenders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [RelaySenders] that keeps the sender of the relay in force, with its signing key and instance id, while an
 * administrator does not save another URL: the instance pushes through one relay.
 *
 * @param relay Makes the sender of a relay URL.
 */
class CachedRelaySenders(private val relay: (String) -> FcmSender) : RelaySenders {
    private val mutex = Mutex()
    private var current: Pair<String, FcmSender>? = null

    override suspend fun sender(relayUrl: String): FcmSender = mutex.withLock {
        current?.takeIf { it.first == relayUrl }?.second ?: relay(relayUrl).also { current = relayUrl to it }
    }
}
