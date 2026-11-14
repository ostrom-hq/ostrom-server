// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/push/CachedPushSenders.kt; see NOTICE.
package run.ostrom.server.notifications.domain

import run.ostrom.server.kernel.settings.InstanceSettings

/** The sender of an Ostrom Push relay, kept while its URL does not change. */
fun interface RelaySenders {
    /**
     * @param relayUrl The relay, as [relayUrlRefusal] accepts it.
     *
     * @return Its sender.
     */
    suspend fun sender(relayUrl: String): FcmSender
}

/**
 * The provider an administrator chose and what sends through it.
 *
 * @property provider The provider.
 * @property sender Its sender; null while it is [PushProvider.OFF] or Firebase has no service account.
 */
class PushInForce(val provider: PushProvider, val sender: FcmSender?)

/**
 * The sender of the provider in the instance's `push` settings ([PUSH_SETTINGS]), read at each push so that a save
 * applies at once on every replica: nothing while `off`, the relay at its URL ([DEFAULT_RELAY_URL] unless set), or the
 * instance's own Firebase project with its service account.
 *
 * @param settings The instance's settings.
 * @param firebase The Firebase senders.
 * @param relays The relay senders.
 */
class PushProviders(
    private val settings: InstanceSettings,
    private val firebase: FirebaseSenders,
    private val relays: RelaySenders,
) {
    /** @return The provider in force and its sender. */
    suspend fun inForce(): PushInForce {
        val values = settings.read(PUSH_SETTINGS.name)
        val provider = PushProvider.of(values[PROVIDER_FIELD])
        val sender = when (provider) {
            PushProvider.OFF -> null
            PushProvider.RELAY -> relays.sender(values[RELAY_URL_FIELD] ?: DEFAULT_RELAY_URL)
            PushProvider.FIREBASE -> values[FIREBASE_SERVICE_ACCOUNT_FIELD]?.let { firebase.sender(it) }
        }
        return PushInForce(provider, sender)
    }
}
