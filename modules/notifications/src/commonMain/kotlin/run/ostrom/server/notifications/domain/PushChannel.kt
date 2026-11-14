// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/Push.kt (PushChannel); see NOTICE.
package run.ostrom.server.notifications.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import run.ostrom.core.api.models.PushPayload
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The id of the push channel, stored with the members' switches. */
const val PUSH_CHANNEL: String = "push"

/**
 * A phone a push can reach: an `fcm` phone with its key, whose registering credential still works.
 *
 * @property device The phone.
 * @property publicKey Its P-256 public key.
 */
private class PushTarget(val device: PushDevice, val publicKey: String)

/**
 * Push to the members' phones (TeamCode's `PushChannel`): one push per `fcm` phone that registered a key and whose
 * credential still works, encrypted for that phone and sent through the provider an administrator chose in the
 * instance's `push` settings (the relay or the instance's own Firebase project). While it is `off`, nobody is
 * reachable. The provider sees a token and a blob; the text exists in clear only on this server and on the phone.
 *
 * @param providers The provider in force.
 * @param encryption The payload's encryption.
 * @param devices The phones.
 * @param credentials Whether a phone's credential still works.
 * @param origin The server's public URL, which account of the app a push is for.
 * @param clock The payload's date.
 */
class PushChannel(
    private val providers: PushProviders,
    private val encryption: PushEncryption,
    private val devices: PushDevicesRepository,
    private val credentials: LiveCredentials,
    private val origin: String,
    private val clock: Clock,
) : NotificationChannel {
    override val id: String = PUSH_CHANNEL

    override suspend fun reachable(orgId: Uuid, memberIds: Set<Uuid>): Set<Uuid> {
        if (sender() == null) return emptySet()
        return memberIds.filter { targets(orgId, it).isNotEmpty() }.toSet()
    }

    override suspend fun deliver(notifications: List<Notification>): Map<Uuid, ChannelResult> {
        val sender = sender() ?: return notifications.associate { it.id to ChannelResult.failed(PUSH_OFF) }
        return coroutineScope {
            notifications.map { notification ->
                async {
                    val targets = targets(notification.orgId, notification.memberId)
                    val results = targets.map { target -> async { target to push(sender, notification, target) } }
                        .awaitAll()
                    forgetUnregistered(results)
                    notification.id to outcome(results.map { it.second })
                }
            }.awaitAll().toMap()
        }
    }

    /**
     * A `test` notification to [memberId]'s phones, sent now; nothing is queued.
     *
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return Each phone's result.
     *
     * @throws PushOffException While no provider is chosen.
     * @throws NoPushPhoneException When the member has no phone push can reach.
     */
    suspend fun test(orgId: Uuid, memberId: Uuid): List<PushResult> {
        val sender = sender() ?: throw PushOffException()
        val targets = targets(orgId, memberId).ifEmpty { throw NoPushPhoneException() }
        val now = clock.now()
        val test = Notification(
            Uuid.random(), orgId, memberId, NotificationKind.WAITING, TEST_TITLE, TEST_BODY, null, null, TEST, now, 0,
        )
        val results = coroutineScope {
            targets.map { target -> async { target to push(sender, test, target, TEST) } }.awaitAll()
        }
        forgetUnregistered(results)
        return results.map { it.second }
    }

    // The sender of the provider in force, or null while push is off or cannot name an account of the app.
    private suspend fun sender(): FcmSender? = if (origin.isEmpty()) null else providers.inForce().sender

    private suspend fun targets(orgId: Uuid, memberId: Uuid): List<PushTarget> =
        devices.ofMember(orgId, memberId).mapNotNull { device ->
            val key = device.publicKey?.takeIf { device.provider == FCM }
            val credential = device.credential
            if (key != null && credential != null && credentials.live(credential.userId, credential)) {
                PushTarget(device, key)
            } else {
                null
            }
        }

    private suspend fun push(
        sender: FcmSender,
        notification: Notification,
        target: PushTarget,
        kind: String = notification.kind.wire,
    ): PushResult {
        val device = target.device
        val payload = PushPayload(
            id = notification.id.toString(),
            origin = origin,
            orgId = device.orgId.toString(),
            memberId = device.memberId.toString(),
            kind = kind,
            title = notification.title,
            body = notification.body,
            sessionId = notification.sessionId,
            waitId = notification.waitId,
            at = notification.createdAt.toEpochMilliseconds(),
        )
        val plaintext = ROUTER_JSON.encodeToString(PushPayload.serializer(), payload)
        // Nothing in clear ever leaves: a phone whose stored key no longer opens fails alone.
        val sealed = try {
            encryption.encrypt(target.publicKey, plaintext)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return PushResult.rejected(BAD_KEY_CODE)
        }
        // Pushes about the same wait replace one another, without the provider learning which wait.
        val push = EncryptedPush(
            token = device.pushToken,
            platform = device.platform,
            payload = sealed,
            collapseKey = encryption.collapseKey("${device.orgId}:${notification.collapseKey}"),
            ttlSeconds = ROUTER_TTL.inWholeSeconds,
        )
        return withTimeoutOrNull(ROUTER_DEADLINE) { sender.send(push) } ?: PushResult.retry(TIMEOUT_CODE)
    }

    // A token the provider no longer knows is forgotten, unless the app already registered it anew.
    private suspend fun forgetUnregistered(results: List<Pair<PushTarget, PushResult>>) {
        results.filter { it.second.status == PushResult.Status.UNREGISTERED }
            .forEach { (target, _) ->
                devices.forgetToken(target.device.orgId, target.device.id, target.device.pushToken)
            }
    }

    /** Delivered when one phone got it; retried when none did and one may still; failed otherwise. */
    private fun outcome(results: List<PushResult>): ChannelResult {
        val retry = results.firstOrNull { it.status == PushResult.Status.RETRY }
        return when {
            results.isEmpty() -> ChannelResult.failed(NO_DEVICE)
            results.any { it.status == PushResult.Status.SENT } -> ChannelResult.DELIVERED
            retry != null -> ChannelResult.retry(retry.error ?: RETRY_CODE)
            results.all { it.status == PushResult.Status.UNREGISTERED } -> ChannelResult.failed(UNREGISTERED_CODE)
            else -> ChannelResult.failed(results.firstNotNullOfOrNull { it.error } ?: REJECTED_CODE)
        }
    }
}

/** The payload's JSON: `v` written, the absent ids left out. */
private val ROUTER_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** A push not taken within this time is tried again later (TeamCode's `PushChannel.SEND_DEADLINE_MILLIS`). */
private val ROUTER_DEADLINE = 10.seconds

/** A notification late by more than an hour is no longer worth showing: the provider drops it. */
private val ROUTER_TTL = 1.hours

private const val PUSH_OFF = "push_off"
private const val NO_DEVICE = "no_device"
private const val TIMEOUT_CODE = "timeout"
private const val RETRY_CODE = "retry"
private const val UNREGISTERED_CODE = "unregistered"
private const val REJECTED_CODE = "rejected"
private const val BAD_KEY_CODE = "push_key_invalid"
private const val TEST = "test"
private const val TEST_TITLE = "Ostrom"
private const val TEST_BODY = "Push notifications work."
