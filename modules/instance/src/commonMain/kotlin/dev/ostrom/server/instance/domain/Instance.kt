// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance.domain

import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * This Ostrom instance: one row, created at the first start and never changed.
 *
 * @property id The stable id of the instance, which receipts and packages name as their issuer.
 * @property createdAt When the instance first started.
 */
data class Instance(
    val id: Uuid,
    val createdAt: Instant,
)

/**
 * A signing key the instance has used. Keys are never forgotten: a receipt signed before a key change must still
 * verify.
 *
 * @property keyId The key id (16 hex characters).
 * @property algorithm The algorithm, `ed25519`.
 * @property publicKeyPem The public key in PEM (SPKI).
 * @property firstSeenAt When the instance first started with this key.
 * @property rotation How the key it replaced handed it over, or null (the first key, or a key changed without its
 * previous one).
 */
data class SigningKey(
    val keyId: String,
    val algorithm: String,
    val publicKeyPem: String,
    val firstSeenAt: Instant,
    val rotation: KeyRotation? = null,
)

/**
 * A new signing key handed over by the key it replaces (docs/receipt-key-rotation.md): a phone that pinned the
 * previous key trusts the new one only with it (app#33, S2-18).
 *
 * @property keyId The previous key's id.
 * @property signature The previous key's Ed25519 signature, in base64, over [KeyRotation.message] of the new key.
 */
data class KeyRotation(val keyId: String, val signature: String) {
    /** The handed-over message. */
    companion object {
        private const val CONTEXT = "ostrom-receipt-key-rotation/v1\n"
        private const val RAW_KEY_BYTES = 32

        /**
         * What the previous key signs: a context line, then the hex of the new key's 32 raw bytes (the end of its
         * SPKI DER), as the app checks it (`keyRotationMessage`).
         *
         * @param publicKeyPem The new key, in SPKI PEM.
         *
         * @return The bytes to sign.
         */
        fun message(publicKeyPem: String): ByteArray {
            val der = Base64.decode(publicKeyPem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
            return (CONTEXT + der.copyOfRange(der.size - RAW_KEY_BYTES, der.size).toHexString()).encodeToByteArray()
        }
    }
}
