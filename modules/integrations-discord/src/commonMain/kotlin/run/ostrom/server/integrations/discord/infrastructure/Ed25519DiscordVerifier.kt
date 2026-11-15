// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.infrastructure

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import run.ostrom.server.integrations.discord.domain.DiscordVerifier
import java.security.GeneralSecurityException

/** `SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING (33 bytes) }`, before the 32 key bytes. */
private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

/**
 * [DiscordVerifier] on Ed25519 (the JDK through cryptography-kotlin). Discord gives the application's public key as
 * its 32 raw bytes in hexadecimal: they are wrapped in the fixed SubjectPublicKeyInfo of Ed25519 (RFC 8410) to decode.
 */
class Ed25519DiscordVerifier : DiscordVerifier {
    override suspend fun verify(publicKey: String, data: ByteArray, signature: String): Boolean {
        val key = hexOrNull(publicKey)?.takeIf { it.size == KEY_LENGTH }
        val bytes = hexOrNull(signature)?.takeIf { it.size == SIGNATURE_LENGTH }
        if (key == null || bytes == null) return false
        return try {
            CryptographyProvider.Default.get(EdDSA).publicKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArray(EdDSA.PublicKey.Format.DER, SPKI_PREFIX + key)
                .signatureVerifier().tryVerifySignature(data, bytes)
        } catch (_: GeneralSecurityException) {
            // 32 bytes that are no point of the curve (the JDK refuses them as a key): nothing verifies with it.
            false
        }
    }

    private companion object {
        const val KEY_LENGTH = 32
        const val SIGNATURE_LENGTH = 64
        const val HEX = 16

        fun hexOrNull(text: String): ByteArray? {
            if (text.length % 2 != 0 || text.any { it.digitToIntOrNull(HEX) == null }) return null
            return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(HEX).toByte() }
        }
    }
}
