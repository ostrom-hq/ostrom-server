// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/Push.kt; see NOTICE.
package run.ostrom.server.notifications.domain

import run.ostrom.core.api.models.PushPayload
import run.ostrom.server.kernel.settings.InstanceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The end-to-end encryption of an `fcm` push (the contract's `PushPayload`): ECIES on P-256, so the push provider sees
 * a token and an opaque blob, and the text exists in clear only on the server and on the phone.
 */
interface PushEncryption {
    /**
     * @param publicKey A phone's public key, as it registers it.
     *
     * @return Whether it is a P-256 public key (SubjectPublicKeyInfo, DER, base64) a push can be encrypted for.
     */
    suspend fun acceptsKey(publicKey: String): Boolean

    /**
     * Encrypts a payload for a phone: `base64(0x01 | ephemeral point (65 bytes) | nonce (12) | AES-256-GCM ciphertext
     * and tag)`, the key being HKDF-SHA256 of the ECDH secret, salt `ephemeral point | phone point`, info
     * `ostrom-push-v1`.
     *
     * @param publicKey The phone's public key, one [acceptsKey] accepted.
     * @param plaintext The payload's JSON.
     *
     * @return The encrypted payload, base64.
     */
    suspend fun encrypt(publicKey: String, plaintext: String): String

    /**
     * @param key What groups the pushes that replace one another.
     *
     * @return An opaque collapse key (32 lowercase hex characters of its SHA-256): the provider sees which pushes
     *   replace one another, not what they are about.
     */
    suspend fun collapseKey(key: String): String
}

/**
 * One encrypted push to one phone, as the provider takes it.
 *
 * @property token The phone's FCM registration token.
 * @property platform `ios` or `android`: how the provider shows it.
 * @property payload The encrypted [PushPayload], base64.
 * @property collapseKey An opaque hash grouping the pushes that replace one another.
 * @property ttlSeconds How long the provider keeps it for a phone that is offline.
 */
data class EncryptedPush(
    val token: String,
    val platform: String,
    val payload: String,
    val collapseKey: String,
    val ttlSeconds: Long,
) {
    // The token never reaches a log.
    override fun toString(): String = "EncryptedPush(platform=$platform, ttlSeconds=$ttlSeconds)"
}

/**
 * What the provider did with a push.
 *
 * @property status The outcome.
 * @property error A short code of ours (`fcm_403`), never the provider's answer.
 */
data class PushResult(val status: Status, val error: String? = null) {
    /** The outcomes. */
    enum class Status {
        /** Sent. */
        SENT,

        /** The provider no longer knows the token: the app was removed or the token rotated. */
        UNREGISTERED,

        /** Unavailable, failing or rate limited: worth another attempt later. */
        RETRY,

        /** Refused for good; the token is kept (the fault may be ours). */
        REJECTED,
    }

    /** The outcomes without a code. */
    companion object {
        /** Sent. */
        val SENT: PushResult = PushResult(Status.SENT)

        /** The token is no longer known. */
        val UNREGISTERED: PushResult = PushResult(Status.UNREGISTERED)

        /**
         * @param error What failed.
         *
         * @return A retryable failure.
         */
        fun retry(error: String): PushResult = PushResult(Status.RETRY, error)

        /**
         * @param error What was refused.
         *
         * @return A refusal for good.
         */
        fun rejected(error: String): PushResult = PushResult(Status.REJECTED, error)
    }
}

/** The provider's edge (the instance's own Firebase project): never logs the token or the payload. */
fun interface FcmSender {
    /**
     * @param push The push.
     *
     * @return What the provider did.
     */
    suspend fun send(push: EncryptedPush): PushResult
}

/** The sender of a Firebase project, kept while its service account does not change. */
fun interface FirebaseSenders {
    /**
     * @param serviceAccountJson The project's service account JSON key, opened from the instance's settings.
     *
     * @return Its sender.
     */
    suspend fun sender(serviceAccountJson: String): FcmSender
}

/**
 * [PushSender] for the `fcm` phones (TeamCode's `PushChannel`): each push is encrypted for the phone's key and sent
 * through the provider an administrator chose in the instance's `push` settings ([PUSH_SETTINGS]). While it is `off`
 * nothing is sent, and nothing is tried again. A token the provider no longer knows is forgotten, unless the app
 * already registered it anew.
 *
 * @param settings The instance's settings.
 * @param senders The Firebase senders.
 * @param encryption The payload's encryption.
 * @param devices The phones, to forget a token.
 * @param origin The server's public URL (`OSTROM_PUBLIC_URL`), which account of the app a push is for.
 * @param clock The payload's date.
 */
@Suppress("LongParameterList") // The settings, the senders, the cipher, the phones, the origin, the clock.
class EncryptedPushSender(
    private val settings: InstanceSettings,
    private val senders: FirebaseSenders,
    private val encryption: PushEncryption,
    private val devices: PushDevicesRepository,
    private val origin: String,
    private val clock: Clock,
) : PushSender {
    override suspend fun send(messages: List<PushMessage>) {
        val values = settings.read(PUSH_SETTINGS.name)
        if (PushProvider.of(values[PROVIDER_FIELD]) != PushProvider.FIREBASE) return
        val account = values[FIREBASE_SERVICE_ACCOUNT_FIELD] ?: throw PushRejectedException(NO_CREDENTIALS)
        // The app tells its accounts apart by the server's URL: without it, a push names no account.
        if (origin.isEmpty()) throw PushRejectedException(NO_ORIGIN)
        val sender = senders.sender(account)
        val results = coroutineScope { messages.map { async { it to send(sender, it) } }.awaitAll() }
        results.filter { it.second.status == PushResult.Status.UNREGISTERED }
            .forEach { (message, _) -> devices.forgetToken(message.device.orgId, message.device.id, message.to) }
        val outcomes = results.map { it.second }
        outcomes.firstOrNull { it.status == PushResult.Status.RETRY }
            ?.let { throw PushNotSentException(it.error ?: RETRY) }
        outcomes.firstOrNull { it.status == PushResult.Status.REJECTED }
            ?.let { throw PushRejectedException(it.error ?: REJECTED) }
    }

    private suspend fun send(sender: FcmSender, message: PushMessage): PushResult {
        val device = message.device
        val payload = PushPayload(
            id = Uuid.random().toString(),
            origin = origin,
            orgId = device.orgId.toString(),
            memberId = device.memberId.toString(),
            kind = message.data[KIND] ?: APPROVAL,
            title = message.title,
            body = message.body,
            approvalId = message.data[APPROVAL_ID],
            at = clock.now().toEpochMilliseconds(),
        )
        val plaintext = PAYLOAD_JSON.encodeToString(PushPayload.serializer(), payload)
        // Pushes about the same request replace one another, without the provider learning which request.
        val group = "${device.orgId}:${payload.kind}:${payload.approvalId.orEmpty()}"
        // Nothing in clear ever leaves: a phone without a key that opens is refused alone.
        val encrypted = device.publicKey?.let { encrypted(it, plaintext) }
            ?: return PushResult.rejected(if (device.publicKey == null) NO_KEY else BAD_KEY)
        val push = EncryptedPush(
            token = device.pushToken,
            platform = device.platform,
            payload = encrypted,
            collapseKey = encryption.collapseKey(group),
            ttlSeconds = TTL.inWholeSeconds,
        )
        return withTimeoutOrNull(SEND_DEADLINE) { sender.send(push) } ?: PushResult.retry(TIMEOUT)
    }

    // The payload encrypted for a phone's key, or null when the stored key no longer opens: that phone only fails.
    private suspend fun encrypted(key: String, plaintext: String): String? = try {
        encryption.encrypt(key, plaintext)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

/**
 * Sends each phone's push through its own provider: `fcm` phones to [fcm], `expo` phones (deprecated) to [expo].
 *
 * @param expo Expo's push service.
 * @param fcm The encrypted pushes.
 */
class ProviderPushSender(private val expo: PushSender, private val fcm: PushSender) : PushSender {
    override suspend fun send(messages: List<PushMessage>) {
        val (encrypted, legacy) = messages.partition { it.device.provider == FCM }
        if (legacy.isNotEmpty()) expo.send(legacy)
        if (encrypted.isNotEmpty()) fcm.send(encrypted)
    }
}

// The payload's JSON: `v` written, an absent approvalId left out.
private val PAYLOAD_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** A push not taken within this time is tried again later. */
private val SEND_DEADLINE = 15.seconds

/** A request for a yes is stale after an hour: the provider drops it rather than deliver it late (TeamCode's TTL). */
private val TTL = 1.hours

private const val KIND = "kind"
private const val APPROVAL = "approval"
private const val APPROVAL_ID = "approvalId"
private const val NO_CREDENTIALS = "push_firebase_credentials_required"
private const val NO_KEY = "push_key_missing"
private const val BAD_KEY = "push_key_invalid"
private const val NO_ORIGIN = "push_public_url_required"
private const val TIMEOUT = "timeout"
private const val RETRY = "retry"
private const val REJECTED = "rejected"
