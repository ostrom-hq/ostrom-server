package dev.ostrom.server.secrets.infrastructure

import dev.ostrom.server.secrets.domain.SecretCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

/**
 * [SecretCipher] with AES-256-GCM (OpenSSL 3, statically linked by cryptography-kotlin). An envelope is
 * `v1:` + base64url without padding of the 12-byte nonce, the ciphertext and the 16-byte tag, with the context as
 * associated data: the layout TeamCode writes (`CredentialCipher`), so an imported TeamCode envelope opens with
 * [open] given TeamCode's key and context.
 *
 * @param key The 32-byte key.
 */
class AesGcmSecretCipher(key: ByteArray) : SecretCipher {
    init {
        require(key.size == KEY_BYTES) { "An AES-256 key is exactly 32 bytes." }
    }

    private val cipher = CryptographyProvider.Default.get(AES.GCM)
        .keyDecoder()
        .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key.copyOf())
        .cipher()

    override suspend fun seal(plaintext: ByteArray, context: String): String {
        require(context.isNotBlank()) { "A secret is always sealed with a context." }
        return PREFIX + base64.encode(cipher.encrypt(plaintext, context.encodeToByteArray()))
    }

    override suspend fun open(envelope: String, context: String): ByteArray? {
        if (!envelope.startsWith(PREFIX) || context.isBlank()) return null
        val bytes = try {
            base64.decode(envelope.substring(PREFIX.length))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size < NONCE_BYTES + TAG_BYTES) return null
        return try {
            cipher.decrypt(bytes, context.encodeToByteArray())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // OpenSSL reports a failed tag check as a provider exception: a wrong key, context or a tampered envelope.
            null
        }
    }

    companion object {
        /** The key size, in bytes. */
        const val KEY_BYTES: Int = 32

        private const val PREFIX = "v1:"
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    }
}
