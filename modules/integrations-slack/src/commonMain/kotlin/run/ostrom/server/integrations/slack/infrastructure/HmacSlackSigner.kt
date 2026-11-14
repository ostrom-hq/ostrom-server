// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.infrastructure

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import run.ostrom.server.integrations.slack.domain.SlackSigner

/** [SlackSigner] with the JDK's HMAC-SHA256 (the kernel links the provider). */
class HmacSlackSigner : SlackSigner {
    override suspend fun sign(secret: String, text: String): String {
        val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
        return key.signatureGenerator().generateSignature(text.encodeToByteArray())
            .joinToString("") { HEX[it.toInt() and BYTE] }
    }
}

private const val BYTE = 0xff

// Top-level, not in a companion: a companion property has a getter nothing calls, which Kover counts.
private val HEX: List<String> = List(256) { it.toString(16).padStart(2, '0') }
