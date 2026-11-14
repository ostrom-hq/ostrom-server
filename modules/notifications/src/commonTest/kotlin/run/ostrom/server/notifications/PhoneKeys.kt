// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64

/** A phone's P-256 key pair, as the app makes it: its public key in SubjectPublicKeyInfo DER, base64. */
class PhoneKeys(val pair: ECDH.KeyPair) {
    /** What the app registers. */
    suspend fun publicKey(): String = Base64.encode(pair.publicKey.encodeToByteArray(EC.PublicKey.Format.DER))

    /**
     * Decrypts a push as the app does (TeamCode's PushPayloadReader): an independent reading of the documented bytes.
     *
     * @param payload The encrypted payload, base64.
     *
     * @return The plaintext.
     */
    suspend fun decrypt(payload: String): String {
        val bytes = Base64.decode(payload)
        require(bytes[0] == 1.toByte()) { "version" }
        val ephemeralPoint = bytes.copyOfRange(1, 66)
        val provider = CryptographyProvider.Default
        val ecdh = provider.get(ECDH)
        val ephemeral = ecdh.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PublicKey.Format.RAW, ephemeralPoint)
        val secret = pair.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(ephemeral)
        val phonePoint = pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
        val key = provider.get(HKDF)
            .secretDerivation(SHA256, 32.bytes, ephemeralPoint + phonePoint, "ostrom-push-v1".encodeToByteArray())
            .deriveSecretToByteArray(secret)
        val cipher = provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher()
        return cipher.decrypt(bytes.copyOfRange(66, bytes.size)).decodeToString()
    }
}

/** A new phone key pair. */
suspend fun phoneKeys(): PhoneKeys =
    PhoneKeys(CryptographyProvider.Default.get(ECDH).keyPairGenerator(EC.Curve.P256).generateKey())

/** A new phone's public key. */
suspend fun phoneKey(): String = phoneKeys().publicKey()
