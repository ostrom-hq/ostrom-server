// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/Push.kt (PushPayloads); see NOTICE.
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.notifications.domain.PushEncryption
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

/**
 * [PushEncryption] with cryptography-kotlin (the JDK): ECIES on P-256, byte for byte TeamCode's `PushPayloads.encrypt`
 * with Ostrom's info string. `base64(0x01 | ephemeral point (65 bytes, uncompressed) | nonce (12) | AES-256-GCM
 * ciphertext and 16-byte tag)`, the key `HKDF-SHA256(ECDH secret, salt = ephemeral point | phone point, info =
 * "ostrom-push-v1", 32 bytes)`.
 */
class EciesPushEncryption : PushEncryption {
    private val provider = CryptographyProvider.Default
    private val ecdh = provider.get(ECDH)

    override suspend fun acceptsKey(publicKey: String): Boolean =
        publicKey.length <= MAX_KEY_CHARS && decode(publicKey) != null

    override suspend fun encrypt(publicKey: String, plaintext: String): String {
        val recipient = requireNotNull(decode(publicKey)) { "Not a P-256 public key." }
        val ephemeral = ecdh.keyPairGenerator(EC.Curve.P256).generateKey()
        val secret = ephemeral.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(recipient)
        val ephemeralPoint = ephemeral.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
        val recipientPoint = recipient.encodeToByteArray(EC.PublicKey.Format.RAW)
        val key = provider.get(HKDF)
            .secretDerivation(SHA256, KEY_BYTES.bytes, ephemeralPoint + recipientPoint, INFO.encodeToByteArray())
            .deriveSecretToByteArray(secret)
        // The cipher puts its random 12-byte nonce first and the 16-byte tag last: the layout the apps read.
        val sealed = provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher()
            .encrypt(plaintext.encodeToByteArray())
        return Base64.encode(byteArrayOf(VERSION) + ephemeralPoint + sealed)
    }

    override suspend fun collapseKey(key: String): String =
        provider.get(SHA256).hasher().hash(key.encodeToByteArray()).toHexString().take(COLLAPSE_CHARS)

    // A P-256 public key in SubjectPublicKeyInfo DER, base64; null for anything else.
    private suspend fun decode(publicKey: String): ECDH.PublicKey? = try {
        ecdh.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PublicKey.Format.DER, Base64.decode(publicKey))
            // A DER key of another curve decodes on some providers: its point is not 65 bytes.
            .takeIf { it.encodeToByteArray(EC.PublicKey.Format.RAW).size == POINT_BYTES }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val VERSION: Byte = 1
        const val INFO = "ostrom-push-v1"
        const val KEY_BYTES = 32
        const val POINT_BYTES = 65
        const val MAX_KEY_CHARS = 255
        const val COLLAPSE_CHARS = 32
    }
}
