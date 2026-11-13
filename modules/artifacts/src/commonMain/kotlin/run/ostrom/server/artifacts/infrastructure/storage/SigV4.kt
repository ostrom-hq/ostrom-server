// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure.storage

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.time.Instant

/**
 * AWS Signature Version 4 for S3 requests (header form), as S3, OVHcloud, Cloudflare R2 and MinIO accept it, written
 * in-house as TeamCode's `SigV4`: one region, the `s3` service, a hex payload hash in `x-amz-content-sha256`.
 */
internal object SigV4 {
    /** The algorithm name. */
    const val ALGORITHM: String = "AWS4-HMAC-SHA256"

    /** The SHA-256 of an empty payload. */
    const val EMPTY_SHA256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private const val SERVICE = "s3"
    private const val DAY_LENGTH = 8
    private const val NIBBLE = 4
    private const val LOW_NIBBLE = 0xf
    private const val BYTE_MASK = 0xff
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    private val SPACES = Regex(" +")

    /**
     * A request to sign.
     *
     * @property method `GET`, `PUT`...
     * @property canonicalUri The already encoded path ([encodePath]).
     * @property query Raw, unencoded pairs.
     * @property headers Every header to sign, `host` and `x-amz-date` included.
     * @property payloadHash The hex SHA-256 of the body.
     */
    data class Request(
        val method: String,
        val canonicalUri: String,
        val query: List<Pair<String, String>>,
        val headers: Map<String, String>,
        val payloadHash: String,
    )

    /**
     * Who signs, where and when.
     *
     * @property accessKeyId The key id.
     * @property secretAccessKey The secret.
     * @property region The region.
     * @property amzDate `yyyyMMddTHHmmssZ` ([amzDate]).
     */
    class Signer(val accessKeyId: String, val secretAccessKey: String, val region: String, val amzDate: String) {
        override fun toString(): String = "Signer(accessKeyId=$accessKeyId, secretAccessKey=[REDACTED])"
    }

    /**
     * The `Authorization` header of a request.
     *
     * @return The header value.
     */
    suspend fun authorization(request: Request, signer: Signer): String {
        val signature = signature(request, signer)
        return "$ALGORITHM Credential=${signer.accessKeyId}/${scope(signer)}, " +
            "SignedHeaders=${signedHeaders(request.headers)}, Signature=$signature"
    }

    /**
     * The signature of a request.
     *
     * @return Lower-case hex.
     */
    suspend fun signature(request: Request, signer: Signer): String {
        val canonical = canonicalRequest(request)
        val digest = sha256(canonical.encodeToByteArray()).toHexString()
        val toSign = "$ALGORITHM\n${signer.amzDate}\n${scope(signer)}\n$digest"
        var key = hmac(("AWS4" + signer.secretAccessKey).encodeToByteArray(), signer.amzDate.take(DAY_LENGTH))
        key = hmac(key, signer.region)
        key = hmac(key, SERVICE)
        key = hmac(key, "aws4_request")
        return hmac(key, toSign).toHexString()
    }

    /**
     * The canonical request: method, path, sorted encoded query, sorted lower-case headers with folded spaces, their
     * names, the payload hash.
     *
     * @return The text that is hashed.
     */
    fun canonicalRequest(request: Request): String {
        val query = request.query.map { (name, value) -> encode(name) to encode(value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }
        val headers = request.headers.entries
            .map { (name, value) -> name.lowercase() to value.trim().replace(SPACES, " ") }
            .sortedBy { it.first }
            .joinToString("") { (name, value) -> "$name:$value\n" }
        return "${request.method}\n${request.canonicalUri}\n$query\n$headers\n${signedHeaders(request.headers)}\n" +
            request.payloadHash
    }

    /**
     * RFC 3986 encoding of one value: every byte but the unreserved characters, `/` included.
     *
     * @return The encoded value.
     */
    fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and BYTE_MASK
            val char = c.toChar()
            if (char in UNRESERVED) {
                append(char)
            } else {
                append('%').append(HEX_DIGITS[c shr NIBBLE]).append(HEX_DIGITS[c and LOW_NIBBLE])
            }
        }
    }

    /**
     * A path whose segments are each [encode]d, so `/` still separates them; S3 is never double-encoded.
     *
     * @return The encoded path.
     */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

    /**
     * The `x-amz-date` of an instant.
     *
     * @return `yyyyMMddTHHmmssZ`, in UTC.
     */
    fun amzDate(at: Instant): String =
        Instant.fromEpochSeconds(at.epochSeconds).toString().filter { it != '-' && it != ':' }

    /**
     * SHA-256.
     *
     * @return The digest.
     */
    suspend fun sha256(bytes: ByteArray): ByteArray = CryptographyProvider.Default.get(SHA256).hasher().hash(bytes)

    private fun signedHeaders(headers: Map<String, String>) =
        headers.keys.map { it.lowercase() }.sorted().joinToString(";")

    private fun scope(signer: Signer) = "${signer.amzDate.take(DAY_LENGTH)}/${signer.region}/$SERVICE/aws4_request"

    private suspend fun hmac(key: ByteArray, data: String): ByteArray =
        CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignature(data.encodeToByteArray())
}
