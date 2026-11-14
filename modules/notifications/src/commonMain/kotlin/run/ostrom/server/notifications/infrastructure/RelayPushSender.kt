// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/push/RelayPushSender.kt; see NOTICE.
package run.ostrom.server.notifications.infrastructure

import run.ostrom.core.api.models.PushRelay
import run.ostrom.core.api.models.PushRelayRegisterRequest
import run.ostrom.core.api.models.PushRelayRegisterResponse
import run.ostrom.core.api.models.PushRelaySendRequest
import run.ostrom.core.api.models.PushRelaySendResponse
import run.ostrom.server.notifications.domain.EncryptedPush
import run.ostrom.server.notifications.domain.FcmSender
import run.ostrom.server.notifications.domain.PushRelayIdentitiesRepository
import run.ostrom.server.notifications.domain.PushRelayIdentity
import run.ostrom.server.notifications.domain.PushResult
import run.ostrom.server.secrets.domain.SecretCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * Sends through an Ostrom Push relay (`push/`), which holds the Firebase project of the published apps (TeamCode's
 * `RelayPushSender`). The relay knows this server by an Ed25519 public key, made at the first push and shared by every
 * replica ([PushRelayIdentitiesRepository], the private key sealed with the secrets key), and registered once; every
 * send is signed ([PushRelay.signingString]). The relay sees a token and an encrypted blob, never a member, a session
 * or the text. Never logs the token, the payload or an answer.
 *
 * @param relayUrl The relay, without a trailing slash.
 * @param identities The identities at the relays.
 * @param cipher The secrets key.
 * @param http The kernel's outbound client: the relay URL is an administrator's, so every connection is checked.
 * @param clock The signature's time.
 */
class RelayPushSender(
    private val relayUrl: String,
    private val identities: PushRelayIdentitiesRepository,
    private val cipher: SecretCipher,
    private val http: HttpClient,
    private val clock: Clock,
) : FcmSender {
    private val mutex = Mutex()
    private var cached: Signer? = null

    /**
     * What signs this server's requests to the relay.
     *
     * @property instanceId The relay's id for the key.
     * @property publicKey The key.
     * @property key The private key.
     */
    private class Signer(val instanceId: String, val publicKey: String, val key: EdDSA.PrivateKey)

    override suspend fun send(push: EncryptedPush): PushResult {
        val signer = signerOrNull()
        val response = signer?.let { signedPost(it, body(push)) }
        return when {
            signer == null -> PushResult.retry("relay_registration")
            response == null -> PushResult.retry("relay_unreachable")
            else -> verdict(signer, response)
        }
    }

    // A status family rather than a range: no bound of a range a real answer never reaches is left for Kover.
    private suspend fun verdict(signer: Signer, response: HttpResponse): PushResult {
        val status = response.status.value
        return when {
            status / STATUS_FAMILY == SUCCESS -> outcome(response)
            // The relay no longer knows the key (its registry was reset): registered again at the next push.
            status == UNAUTHORIZED -> {
                forget(signer)
                PushResult.retry("relay_unknown_instance")
            }
            status == FORBIDDEN -> PushResult.rejected("relay_instance_blocked")
            status == TIMEOUT || status == TOO_MANY -> PushResult.retry("relay_rate_limited")
            status / STATUS_FAMILY == SERVER_ERROR -> PushResult.retry("relay_unavailable")
            else -> PushResult.rejected("relay_refused")
        }
    }

    // The relay's verdict; an answer that is not one is a refusal (a SerializationException is an IllegalArgument one).
    private suspend fun outcome(response: HttpResponse): PushResult {
        val verdict = try {
            RELAY_JSON.decodeFromString(PushRelaySendResponse.serializer(), response.bodyAsText()).status
        } catch (_: IllegalArgumentException) {
            null
        }
        return when (verdict) {
            "sent" -> PushResult.SENT
            "unregistered" -> PushResult.UNREGISTERED
            "retry" -> PushResult.retry("provider_unavailable")
            else -> PushResult.rejected("relay_rejected")
        }
    }

    // The signer, or null when the key does not open or the relay refused or missed the registration.
    private suspend fun signerOrNull(): Signer? = try {
        signer()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    // The key pair, made once for every replica, and the instance id, asked of the relay once per key.
    private suspend fun signer(): Signer? = mutex.withLock {
        cached ?: run {
            val identity = identities.find(relayUrl) ?: identities.create(relayUrl, newIdentity())
            val der = cipher.open(identity.sealedPrivateKey, context(relayUrl)) ?: return@withLock null
            val key = EDDSA.privateKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArray(EdDSA.PrivateKey.Format.DER, der)
            // The relay answers the same id for the same key, so replicas registering together agree.
            val instanceId = identity.instanceId
                ?: register(identity.publicKey).also { identities.setInstance(relayUrl, identity.publicKey, it) }
            Signer(instanceId, identity.publicKey, key).also { cached = it }
        }
    }

    private suspend fun forget(signer: Signer) = mutex.withLock {
        if (cached === signer) {
            cached = null
            identities.setInstance(relayUrl, signer.publicKey, null)
        }
    }

    private suspend fun register(publicKey: String): String {
        val response = http.post("$relayUrl${PushRelay.REGISTER_PATH}") {
            contentType(ContentType.Application.Json)
            val request = PushRelayRegisterRequest(publicKey)
            setBody(RELAY_JSON.encodeToString(PushRelayRegisterRequest.serializer(), request))
        }
        check(response.status.value / STATUS_FAMILY == SUCCESS) { "The relay refused the registration." }
        val id = RELAY_JSON.decodeFromString(PushRelayRegisterResponse.serializer(), response.bodyAsText()).instanceId
        // The id goes into a header: one the relay could not have meant is refused.
        check(INSTANCE_ID.matches(id)) { "The relay answered an instance id of another form." }
        return id
    }

    // The relay's answer, or null when it did not answer (connection, timeout).
    private suspend fun signedPost(signer: Signer, body: String): HttpResponse? = try {
        val timestamp = clock.now().epochSeconds
        val digest = SHA.hasher().hash(body.encodeToByteArray()).toHexString()
        val signing = PushRelay.signingString("POST", PushRelay.SEND_PATH, timestamp, digest)
        val signature = signer.key.signatureGenerator().generateSignature(signing.encodeToByteArray())
        http.post("$relayUrl${PushRelay.SEND_PATH}") {
            contentType(ContentType.Application.Json)
            header(PushRelay.INSTANCE_HEADER, signer.instanceId)
            header(PushRelay.TIMESTAMP_HEADER, timestamp.toString())
            header(PushRelay.SIGNATURE_HEADER, BASE64URL.encode(signature))
            setBody(body)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun newIdentity(): PushRelayIdentity {
        val pair = EDDSA.keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
        val publicKey = BASE64URL.encode(pair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW))
        val sealed = cipher.seal(pair.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.DER), context(relayUrl))
        return PushRelayIdentity(publicKey, sealed, null)
    }

    /** The relay protocol's constants. */
    companion object {
        /**
         * @param relayUrl The relay.
         *
         * @return What the private key is sealed with: a key copied to another relay's row does not open.
         */
        fun context(relayUrl: String): String = "push-relay:$relayUrl"
    }
}

private fun body(push: EncryptedPush): String = RELAY_JSON.encodeToString(
    PushRelaySendRequest.serializer(),
    PushRelaySendRequest(push.token, push.platform, push.payload, push.collapseKey, push.ttlSeconds),
)

private val EDDSA get() = CryptographyProvider.Default.get(EdDSA)
private val SHA get() = CryptographyProvider.Default.get(SHA256)
private val BASE64URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
private val RELAY_JSON = Json { ignoreUnknownKeys = true }

/** The relay's ids are its keys, base64url: letters, digits, `-` and `_`, at most 64. */
private val INSTANCE_ID = Regex("[A-Za-z0-9_-]{1,64}")

private const val STATUS_FAMILY = 100
private const val SUCCESS = 2
private const val SERVER_ERROR = 5
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val TIMEOUT = 408
private const val TOO_MANY = 429
