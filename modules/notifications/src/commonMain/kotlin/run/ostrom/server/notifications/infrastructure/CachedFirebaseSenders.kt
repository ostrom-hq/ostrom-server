// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/push/CachedPushSenders.kt; see NOTICE.
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.notifications.domain.FcmSender
import run.ostrom.server.notifications.domain.FirebaseSenders
import run.ostrom.server.notifications.domain.FirebaseServiceAccount
import run.ostrom.server.notifications.domain.PushResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [FirebaseSenders] that keeps the sender of the service account in force, with its access token, while an
 * administrator does not save another: the instance has one Firebase project. A key that no longer parses (saved by
 * an older server) sends nothing and is refused for good.
 *
 * @param firebase Makes the sender of a service account.
 */
class CachedFirebaseSenders(private val firebase: (FirebaseServiceAccount) -> FcmSender) : FirebaseSenders {
    private val mutex = Mutex()
    private var current: Pair<String, FcmSender>? = null

    override suspend fun sender(serviceAccountJson: String): FcmSender = mutex.withLock {
        current?.takeIf { it.first == serviceAccountJson }?.second ?: run {
            val account = FirebaseServiceAccount.parse(serviceAccountJson)
            val sender = if (account == null) INVALID else firebase(account)
            current = serviceAccountJson to sender
            sender
        }
    }
}

private val INVALID = FcmSender { PushResult.rejected("push_firebase_credentials_invalid") }
