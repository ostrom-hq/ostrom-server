package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.identity.domain.SecretGenerator
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.USER_CODE_ALPHABET
import dev.ostrom.server.identity.domain.USER_CODE_LETTERS
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/** URL-safe base64 without padding: secrets go in headers, cookies and URLs unchanged. */
internal val UrlBase64: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/**
 * [SecretGenerator] on the platform's cryptographically secure generator, through [Uuid.random] (a version 4 UUID has
 * 122 random bits): two of them make a 244-bit secret.
 */
class SecureSecretGenerator : SecretGenerator {
    override fun secret(): String = UrlBase64.encode(Uuid.random().toByteArray() + Uuid.random().toByteArray())

    override fun userCode(): String {
        val code = StringBuilder(USER_CODE_LETTERS + 1)
        while (code.length < USER_CODE_LETTERS + 1) {
            Uuid.random().toByteArray().forEachIndexed { index, byte ->
                val value = byte.toInt() and BYTE_MASK
                // Bytes 6 and 8 carry the UUID version and variant bits; the others are random. Rejection sampling
                // keeps only bytes below a multiple of the alphabet size, so no letter is likelier than another.
                if (index == VERSION_BYTE || index == VARIANT_BYTE || value >= UNBIASED_LIMIT) return@forEachIndexed
                if (code.length == USER_CODE_LETTERS + 1) return@forEachIndexed
                if (code.length == USER_CODE_LETTERS / 2) code.append('-')
                code.append(USER_CODE_ALPHABET[value % USER_CODE_ALPHABET.length])
            }
        }
        return code.toString()
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val VERSION_BYTE = 6
        const val VARIANT_BYTE = 8
        val UNBIASED_LIMIT = 256 - 256 % USER_CODE_ALPHABET.length
    }
}

/** [SecretHasher] on SHA-256 (OpenSSL, linked by the kernel's cryptography provider). */
class Sha256SecretHasher : SecretHasher {
    override suspend fun hash(secret: String): String = sha256(secret.encodeToByteArray()).toHexString()

    override suspend fun csrfToken(sessionSecret: String): String =
        UrlBase64.encode(sha256((CSRF_DOMAIN + sessionSecret).encodeToByteArray()))

    override suspend fun pkceChallenge(verifier: String): String = UrlBase64.encode(sha256(verifier.encodeToByteArray()))

    private suspend fun sha256(data: ByteArray): ByteArray = CryptographyProvider.Default.get(SHA256).hasher().hash(data)

    private companion object {
        /** Separates the CSRF derivation from every other hash of the same secret. */
        const val CSRF_DOMAIN = "ostrom-csrf-v1\u0000"
    }
}
