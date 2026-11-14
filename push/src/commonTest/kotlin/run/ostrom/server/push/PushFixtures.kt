// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/RelayTest.kt; see NOTICE.
package run.ostrom.server.push

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.PushRelay
import run.ostrom.core.api.models.PushRelaySendRequest
import run.ostrom.core.api.models.PushRelaySendResponse

/** An instance of the relay's clients: an Ed25519 key pair, whose public key is its id. */
internal class Instance(val id: String, private val sign: suspend (ByteArray) -> ByteArray) {
    /** The three headers of a signed `POST /v1/send` of [body] made at [at]. */
    suspend fun headers(body: ByteArray, at: Long): SignedHeaders {
        val hash = Relay.hex(CryptographyProvider.Default.get(SHA256).hasher().hash(body))
        val signing = PushRelay.signingString("POST", PushRelay.SEND_PATH, at, hash)
        return SignedHeaders(id, at.toString(), urlSafe.encode(sign(signing.encodeToByteArray())))
    }

    companion object {
        val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        /** A new key pair. */
        suspend fun generate(): Instance {
            val pair = CryptographyProvider.Default.get(EdDSA).keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
            val raw = pair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW)
            val signer = pair.privateKey.signatureGenerator()
            return Instance(urlSafe.encode(raw)) { signer.generateSignature(it) }
        }
    }
}

internal data class SignedHeaders(val instance: String, val timestamp: String, val signature: String)

internal val sampleRequest = PushRelaySendRequest(
    token = "t".repeat(40),
    platform = "ios",
    payload = "c2VhbGVkIHBheWxvYWQgYmxvYg==",
    collapseKey = "0123456789abcdef",
    ttlSeconds = 3600,
)

internal fun bodyOf(request: PushRelaySendRequest = sampleRequest): ByteArray =
    OstromJson.encodeToString(request).encodeToByteArray()

internal val parseRequest: (ByteArray) -> PushRelaySendRequest? =
    { decodeOrNull(PushRelaySendRequest.serializer(), it) }

/** A provider that records what it is asked to send and answers [answer]. */
internal class FakeFcm(var answer: PushRelaySendResponse = PushRelaySendResponse("sent")) : FcmSender {
    val sent = mutableListOf<PushRelaySendRequest>()

    override suspend fun send(request: PushRelaySendRequest): PushRelaySendResponse {
        sent += request
        return answer
    }
}
