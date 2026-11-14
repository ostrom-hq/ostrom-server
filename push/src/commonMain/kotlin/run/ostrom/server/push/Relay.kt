// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Relay.kt; see NOTICE.
package run.ostrom.server.push

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import run.ostrom.core.api.models.PushRelay
import run.ostrom.core.api.models.PushRelaySendRequest
import run.ostrom.core.api.models.PushRelaySendResponse

private const val STATUS_OK = 200
private const val STATUS_BAD_REQUEST = 400
private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_FORBIDDEN = 403
private const val STATUS_TOO_MANY_REQUESTS = 429
private const val SECONDS_PER_MINUTE = 60L
private const val MAX_INSTANCE_ID_CHARS = 64
private const val PUBLIC_KEY_BYTES = 32
private const val SIGNATURE_BYTES = 64
private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16

private fun rejected(error: String) = PushRelaySendResponse("rejected", error = error)

private fun unauthorized(error: String) = Verdict.Refused(RelayAnswer(STATUS_UNAUTHORIZED, rejected(error)))

/**
 * The relay (push/README.md, "Relay protocol"). It keeps no registry: an instance's
 * id is its Ed25519 public key (raw 32 bytes, base64url), so any replica of the relay checks a signature without shared
 * state, and a restart forgets nothing. An instance is cut off by listing its id in [blocked]. Quotas and the replay
 * guard are per relay process: the Cloud Run service runs one instance (see push/README.md). Nothing about a request
 * is logged: not its token, its payload or its instance.
 *
 * @param fcm The provider.
 * @param blocked The instance ids cut off by hand.
 * @param limits The quotas, per minute.
 * @param now The time, Unix seconds.
 */
public class Relay(
    private val fcm: FcmSender,
    private val blocked: Set<String> = emptySet(),
    limits: RelayLimits = RelayLimits(),
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val crypto = CryptographyProvider.Default
    private val keys = crypto.get(EdDSA).publicKeyDecoder(EdDSA.Curve.Ed25519)
    private val sha256 = crypto.get(SHA256).hasher()
    private val quotas = Quotas(limits)
    private val seen = ReplayGuard()

    /**
     * `POST /v1/instances`: a valid Ed25519 key is its own id; the same key always gets the same id.
     *
     * @param publicKey The raw 32 bytes, base64url.
     *
     * @return The instance id, or null when it is not a key.
     */
    public fun register(publicKey: String): String? = publicKey.takeIf { parseKey(it) != null }

    /**
     * `POST /v1/send`: the signed request, checked, rate limited, validated, then sent.
     *
     * @param instanceId The [PushRelay.INSTANCE_HEADER].
     * @param timestamp The [PushRelay.TIMESTAMP_HEADER].
     * @param signature The [PushRelay.SIGNATURE_HEADER].
     * @param body The raw request body, the bytes the signature covers.
     * @param parse Reads the body, or null when it is not a request.
     *
     * @return The answer to send back.
     */
    public suspend fun send(
        instanceId: String?,
        timestamp: String?,
        signature: String?,
        body: ByteArray,
        parse: (ByteArray) -> PushRelaySendRequest?,
    ): RelayAnswer = when (val verdict = authenticate(instanceId, timestamp, signature.orEmpty(), body)) {
        is Verdict.Refused -> verdict.answer
        is Verdict.Signed -> admit(verdict, body, parse)
    }

    private suspend fun authenticate(
        instanceId: String?,
        timestamp: String?,
        signature: String,
        body: ByteArray,
    ): Verdict {
        val id = instanceId.orEmpty()
        val key = id.takeIf { it.length <= MAX_INSTANCE_ID_CHARS }?.let(::parseKey)
        val at = timestamp?.toLongOrNull()
        val signed = decode(signature)?.takeIf { it.size == SIGNATURE_BYTES }
        return when {
            key == null -> unauthorized("unknown_instance")
            at == null -> unauthorized("bad_signature")
            abs(now() - at) > PushRelay.MAX_SKEW_SECONDS -> unauthorized("stale_request")
            signed == null -> unauthorized("bad_signature")
            !verifies(key, signed, at, body) -> unauthorized("bad_signature")
            else -> Verdict.Signed(id, at, signature)
        }
    }

    private suspend fun verifies(key: EdDSA.PublicKey, signature: ByteArray, at: Long, body: ByteArray): Boolean {
        val signing = PushRelay.signingString("POST", PushRelay.SEND_PATH, at, hex(sha256.hash(body)))
        return key.signatureVerifier().tryVerifySignature(signing.encodeToByteArray(), signature)
    }

    private suspend fun admit(
        signed: Verdict.Signed,
        body: ByteArray,
        parse: (ByteArray) -> PushRelaySendRequest?,
    ): RelayAnswer =
        when {
            signed.id in blocked -> RelayAnswer(STATUS_FORBIDDEN, rejected("instance_blocked"))
            // A request seen once is not sent twice: a copy within the skew window is answered as sent.
            !seen.first(signed.signature, signed.at, now()) -> RelayAnswer(STATUS_OK, PushRelaySendResponse("sent"))
            else -> process(signed.id, body, parse)
        }

    private suspend fun process(
        id: String,
        body: ByteArray,
        parse: (ByteArray) -> PushRelaySendRequest?,
    ): RelayAnswer {
        val request = parse(body) ?: return RelayAnswer(STATUS_BAD_REQUEST, rejected("invalid_request"))
        return validate(request)?.let { RelayAnswer(STATUS_BAD_REQUEST, rejected(it)) } ?: throttled(id, request)
    }

    private suspend fun throttled(id: String, request: PushRelaySendRequest): RelayAnswer =
        if (quotas.take(id, request.token, now() / SECONDS_PER_MINUTE)) forward(request) else tooManyRequests()

    private fun tooManyRequests(): RelayAnswer {
        val wait = SECONDS_PER_MINUTE - now() % SECONDS_PER_MINUTE
        return RelayAnswer(STATUS_TOO_MANY_REQUESTS, PushRelaySendResponse("retry", wait, "rate_limited"), wait)
    }

    private suspend fun forward(request: PushRelaySendRequest): RelayAnswer = try {
        RelayAnswer(STATUS_OK, fcm.send(request))
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        RelayAnswer(STATUS_OK, PushRelaySendResponse("retry", error = "fcm_unreachable"))
    }

    private fun parseKey(base64: String): EdDSA.PublicKey? = decode(base64)?.takeIf { it.size == PUBLIC_KEY_BYTES }
        ?.let { keys.decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, it) }

    /** The relay's validation of a request, and the codecs of the protocol. */
    public companion object {
        private val tokenPattern = Regex("[A-Za-z0-9_:\\-.]{20,${PushRelay.MAX_TOKEN_CHARS}}")
        private val payloadPattern = Regex("[A-Za-z0-9+/=]{16,${PushRelay.MAX_PAYLOAD_CHARS}}")
        private val collapsePattern = Regex("[a-f0-9]{1,64}")
        private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        /** The longest a push may be kept by FCM: one day. */
        public const val MAX_TTL_SECONDS: Long = 86_400L

        /**
         * Why a request is not acceptable, as a short code, or null when it is.
         *
         * @param request The request to check.
         */
        public fun validate(request: PushRelaySendRequest): String? = when {
            !tokenPattern.matches(request.token) -> "invalid_token"
            request.platform != "ios" && request.platform != "android" -> "invalid_platform"
            !payloadPattern.matches(request.payload) -> "invalid_payload"
            !collapsePattern.matches(request.collapseKey) -> "invalid_collapse_key"
            request.ttlSeconds !in 0..MAX_TTL_SECONDS -> "invalid_ttl"
            else -> null
        }

        /** Reads base64url with or without padding, or null when [value] is not. */
        public fun decode(value: String): ByteArray? = try {
            urlSafe.decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }

        /** Lowercase hex of [bytes]. */
        public fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }
    }
}
