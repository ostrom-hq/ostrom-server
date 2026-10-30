// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.infrastructure

import dev.ostrom.server.automations.domain.WebhookSecrets
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.uuid.Uuid

/**
 * [WebhookSecrets] derived from the instance key: an automation's secret is the SHA-256 of the instance's Ed25519
 * signature (deterministic, RFC 8032) of a label naming the automation and the secret's generation. Nothing is
 * stored: a database copy alone never gives a secret, and rotating is a new generation. Replacing the instance key
 * changes every secret, which senders then get again by a rotation.
 *
 * @param signer The instance key.
 */
internal class WebhookSecretsImpl(private val signer: InstanceSigner) : WebhookSecrets {
    private val hasher = CryptographyProvider.Default.get(SHA256).hasher()

    override suspend fun secret(automationId: Uuid, generation: Int): String {
        val seed = signer.sign("$LABEL$automationId/$generation".encodeToByteArray())
        return PREFIX + hasher.hash(seed).toHexString().take(SECRET_HEX)
    }

    // HMAC (RFC 2104) over the provider's SHA-256, the one primitive every target already uses.
    override suspend fun sign(secret: String, data: ByteArray): ByteArray {
        val key = secret.encodeToByteArray().let { if (it.size > BLOCK) hasher.hash(it) else it }.copyOf(BLOCK)
        val inner = ByteArray(BLOCK) { (key[it].toInt() xor INNER_PAD).toByte() }
        val outer = ByteArray(BLOCK) { (key[it].toInt() xor OUTER_PAD).toByte() }
        return hasher.hash(outer + hasher.hash(inner + data))
    }

    override suspend fun digest(data: ByteArray): String = hasher.hash(data).toHexString()

    private companion object {
        /** The label the instance signs; no other signed message of the instance starts with it. */
        const val LABEL = "ostrom/automations/webhook-secret/v1/"
        const val PREFIX = "whsec_"
        const val SECRET_HEX = 48
        const val BLOCK = 64
        const val INNER_PAD = 0x36
        const val OUTER_PAD = 0x5c
    }
}
