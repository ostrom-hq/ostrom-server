// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailNotSentException
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.notifications.domain.Approver
import run.ostrom.server.notifications.domain.Approvers
import run.ostrom.server.notifications.domain.LiveCredentials
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushDevicesRepository
import run.ostrom.server.notifications.domain.PushMessage
import run.ostrom.server.notifications.domain.PushNotSentException
import run.ostrom.server.notifications.domain.PushSender
import kotlinx.coroutines.channels.Channel
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The phones in memory, and how many the last save let a member keep. */
class InMemoryPushDevicesRepository : PushDevicesRepository {
    val devices = mutableListOf<PushDevice>()
    var kept: Int? = null

    override suspend fun save(device: PushDevice, keep: Int): PushDevice {
        kept = keep
        devices += device
        return device
    }

    override suspend fun delete(orgId: Uuid, memberId: Uuid, id: Uuid): Boolean =
        devices.removeAll { it.orgId == orgId && it.memberId == memberId && it.id == id }

    override suspend fun ofMember(orgId: Uuid, memberId: Uuid): List<PushDevice> =
        devices.filter { it.orgId == orgId && it.memberId == memberId }

    override suspend fun forget(orgId: Uuid, userId: Uuid, credentialId: Uuid?) {
        devices.removeAll { device ->
            val credential = device.credential
            device.orgId == orgId && credential?.userId == userId &&
                (if (credentialId == null) credential.browser else credential.credentialId == credentialId)
        }
    }

    /** The tokens forgotten, as (device, token). */
    val forgotten = mutableListOf<Pair<Uuid, String>>()

    override suspend fun forgetToken(orgId: Uuid, id: Uuid, pushToken: String) {
        forgotten += id to pushToken
        devices.removeAll { it.orgId == orgId && it.id == id && it.pushToken == pushToken }
    }

    override suspend fun deleteAll(orgId: Uuid) {
        devices.removeAll { it.orgId == orgId }
    }
}

/**
 * A phone of a test.
 *
 * @param token Its push token.
 * @param provider `expo` or `fcm`.
 * @param platform `ios` or `android`.
 * @param publicKey Its public key, for `fcm`.
 *
 * @return It, in a new organization.
 */
fun testPhone(
    token: String,
    provider: String = "expo",
    platform: String = "ios",
    publicKey: String? = null,
): PushDevice = PushDevice(
    Uuid.random(), Uuid.random(), Uuid.random(), token, provider, platform, null, Instant.DISTANT_PAST,
    Instant.DISTANT_PAST, null, publicKey,
)

/** A clock that says [now]. */
class TestClock(var now: Instant = Instant.parse("2026-11-03T08:00:00Z")) : Clock {
    override fun now(): Instant = now
}

/**
 * A push service that records what it is sent, and refuses the first [failures] calls.
 *
 * @param failures How many calls fail before the service answers.
 */
class RecordingPushSender(var failures: Int = 0) : PushSender {
    val calls = mutableListOf<List<PushMessage>>()

    /** Each delivered batch, as it arrives: a test running the outbox for real waits on it. */
    val delivered = Channel<List<PushMessage>>(Channel.UNLIMITED)

    override suspend fun send(messages: List<PushMessage>) {
        calls += messages
        if (failures > 0) {
            failures--
            throw PushNotSentException("Expo refused (503).")
        }
        delivered.send(messages)
    }
}

/**
 * A mail provider that records what it is sent, and refuses the first [failures] calls.
 *
 * @param failures How many calls fail before the provider answers.
 */
class RecordingMailSender(var failures: Int = 0) : MailSender {
    val calls = mutableListOf<Mail>()

    /** Each delivered mail, as it arrives. */
    val delivered = Channel<Mail>(Channel.UNLIMITED)

    override suspend fun send(mail: Mail) {
        calls += mail
        if (failures > 0) {
            failures--
            throw MailNotSentException()
        }
        delivered.send(mail)
    }
}

/** A logger that keeps each event: its level, message and fields. */
class RecordingLogger : Logger {
    val events = mutableListOf<Triple<LogLevel, String, Map<String, Any?>>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        events += Triple(level, message, fields)
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

/** The approvers of a test, by login (any case). */
class MapApprovers(private val byLogin: Map<String, Approver>) : Approvers {
    val asked = mutableListOf<Pair<Uuid, String>>()

    override suspend fun reach(orgId: Uuid, login: String): Approver? {
        asked += orgId to login
        return byLogin[login.lowercase()]
    }
}

/** The credentials that still work. */
class SetLiveCredentials(private val live: Set<Uuid>) : LiveCredentials {
    override suspend fun live(userId: Uuid, credential: PushCredential): Boolean = credential.credentialId in live
}
