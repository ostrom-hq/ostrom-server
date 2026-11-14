// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/push/RelayPushSender.kt; see NOTICE.
package run.ostrom.server.notifications.domain

/**
 * This server's identity at a relay: an Ed25519 key pair every replica signs with, and the id the relay gave it.
 *
 * @property publicKey The raw 32 bytes of the public key, base64url without padding, as the relay takes it.
 * @property sealedPrivateKey The private key (PKCS#8 DER), sealed with the secrets key; never logged.
 * @property instanceId The relay's id for the key; null until the relay answered the registration.
 */
data class PushRelayIdentity(val publicKey: String, val sealedPrivateKey: String, val instanceId: String?) {
    // The sealed key never reaches a log.
    override fun toString(): String = "PushRelayIdentity(publicKey=$publicKey, instanceId=$instanceId)"
}

/** The instance's identities at the relays, one per relay URL, shared by every replica. */
interface PushRelayIdentitiesRepository {
    /**
     * @param relayUrl The relay.
     *
     * @return The identity at it, or null before the first push through it.
     */
    suspend fun find(relayUrl: String): PushRelayIdentity?

    /**
     * Keeps [identity] unless another replica kept one first.
     *
     * @param relayUrl The relay.
     * @param identity A new identity.
     *
     * @return The identity in force: [identity], or the one kept first.
     */
    suspend fun create(relayUrl: String, identity: PushRelayIdentity): PushRelayIdentity

    /**
     * Records the relay's id for a key, or forgets it (null) when the relay no longer knows the key.
     *
     * @param relayUrl The relay.
     * @param publicKey The key, so that a key replaced meanwhile is left alone.
     * @param instanceId The id, or null.
     */
    suspend fun setInstance(relayUrl: String, publicKey: String, instanceId: String?)
}
