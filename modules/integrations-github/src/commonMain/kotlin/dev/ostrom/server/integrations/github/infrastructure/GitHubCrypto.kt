// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * The GitHub App's JWT (RS256), which authenticates the App itself: it is only exchanged for installation tokens
 * and used to read an installation. Issued 60 s in the past against clock drift, valid 9 minutes (GitHub's limit
 * is 10).
 *
 * @param appId The App's id.
 * @param privateKeyPem Its private key, PKCS#1 (`BEGIN RSA PRIVATE KEY`, as GitHub gives it) or PKCS#8.
 * @param clock The clock.
 */
class AppJwt(private val appId: String, private val privateKeyPem: String, private val clock: Clock) {
    private var key: RSA.PKCS1.PrivateKey? = null

    /** @return A fresh JWT. */
    suspend fun token(): String {
        val issuedAt = clock.now().epochSeconds - DRIFT_SECONDS
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = buildJsonObject {
            put("iat", issuedAt)
            put("exp", issuedAt + LIFETIME_SECONDS)
            put("iss", appId)
        }.toString()
        val signingInput = "${base64Url(header.encodeToByteArray())}.${base64Url(claims.encodeToByteArray())}"
        val signature = privateKey().signatureGenerator().generateSignature(signingInput.encodeToByteArray())
        return "$signingInput.${base64Url(signature)}"
    }

    private suspend fun privateKey(): RSA.PKCS1.PrivateKey = key ?: run {
        val format = if ("BEGIN RSA PRIVATE KEY" in privateKeyPem) {
            RSA.PrivateKey.Format.PEM.PKCS1
        } else {
            RSA.PrivateKey.Format.PEM.Generic
        }
        CryptographyProvider.Default.get(RSA.PKCS1).privateKeyDecoder(SHA256)
            .decodeFromByteArray(format, privateKeyPem.encodeToByteArray())
            .also { key = it }
    }

    private companion object {
        const val DRIFT_SECONDS = 60L
        const val LIFETIME_SECONDS = 540L
    }
}

/**
 * Checks GitHub's `X-Hub-Signature-256` on a webhook body: HMAC-SHA256 with the App's webhook secret, compared in
 * constant time.
 *
 * @param secret The webhook secret, or null: then no webhook is ever accepted.
 */
class WebhookSignature(private val secret: String?) {
    /**
     * @param body The raw body, as received.
     * @param header The `X-Hub-Signature-256` header.
     * @return True only for the signature of this body with this secret.
     */
    suspend fun verify(body: ByteArray, header: String?): Boolean {
        if (secret == null || header == null || !header.startsWith(PREFIX)) return false
        val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
        val mac = key.signatureGenerator().generateSignature(body)
        val expected = (PREFIX + mac.joinToString("") { HEX[it.toInt() and BYTE] }).encodeToByteArray()
        val got = header.encodeToByteArray()
        var difference = expected.size xor got.size
        for (index in expected.indices) {
            difference = difference or (expected[index].toInt() xor (got.getOrNull(index)?.toInt() ?: 0))
        }
        return difference == 0
    }

    private companion object {
        const val PREFIX = "sha256="
        const val BYTE = 0xff
    }
}

internal fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val HEX: List<String> = List(256) { it.toString(16).padStart(2, '0') }
