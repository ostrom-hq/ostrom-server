// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.infrastructure

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import run.ostrom.server.events.domain.WebhookSigner
import kotlin.io.encoding.Base64

/** [WebhookSigner] on cryptography-kotlin: HMAC-SHA256 keyed with the whole secret string, as TeamCode signs. */
class HmacWebhookSigner : WebhookSigner {
    override suspend fun signature(secret: String, timestampSeconds: Long, body: String): String {
        val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
        val mac = key.signatureGenerator().generateSignature("$timestampSeconds.$body".encodeToByteArray())
        return "v1=" + mac.toHexString()
    }

    override fun newSecret(): String = PREFIX + URL.encode(CryptographyRandom.nextBytes(BYTES))

    private companion object {
        const val PREFIX = "whsec_"
        const val BYTES = 32
        val URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}
