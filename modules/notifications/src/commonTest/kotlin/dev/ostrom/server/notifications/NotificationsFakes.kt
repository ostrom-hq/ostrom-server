// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.notifications.domain.EmailMessage
import dev.ostrom.server.notifications.domain.EmailSender
import dev.ostrom.server.notifications.domain.PersonEmails
import dev.ostrom.server.notifications.domain.PushDevice
import dev.ostrom.server.notifications.domain.PushDevicesRepository
import dev.ostrom.server.notifications.domain.PushMessage
import dev.ostrom.server.notifications.domain.PushSender
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
val OTHER_ORG: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
val NOW: Instant = Instant.parse("2026-10-30T09:00:00Z")
const val TOKEN = "ExponentPushToken[abcdefgh1234]"

/** A pending gate of [ORG], asked by `lyes`. */
fun gate(label: String = "Merge into main"): Gate = Gate(
    id = Uuid.parse("00000000-0000-0000-0000-0000000000a1"),
    orgId = ORG,
    key = "push",
    subject = GateSubject(repo = "acme/api", actionClass = "push-main", command = "git push", scope = "session:s"),
    node = null,
    label = label,
    mode = GateMode.ENFORCED,
    status = GateStatus.PENDING,
    askedBy = listOf("lyes"),
    askedVia = setOf(AskedVia.HOOK),
    sessionIds = listOf("s"),
    shownDigest = "shown",
    evidence = emptyList(),
    createdAt = NOW,
)

/** A request of [gate] for [approver]. */
fun requestOf(approver: String, status: RequestStatus = RequestStatus.PENDING, n: Int = 1) = ApprovalRequest(
    id = Uuid.parse("00000000-0000-0000-0000-00000000000$n"),
    gateId = gate().id,
    orgId = ORG,
    approverId = approver,
    status = status,
    createdAt = NOW,
)

/** A phone of [person] in [ORG]. */
fun device(person: String, token: String = TOKEN) =
    PushDevice(Uuid.random(), ORG, person, token, "expo", "ios", null, NOW)

/** The phones in memory, unique per organization and token as PostgreSQL keeps them. */
class MemoryPushDevices : PushDevicesRepository {
    val devices = mutableListOf<PushDevice>()

    override suspend fun devices(orgId: Uuid, personId: String): List<PushDevice> =
        devices.filter { it.orgId == orgId && it.personId == personId }

    override suspend fun save(device: PushDevice): PushDevice {
        val existing = devices.firstOrNull { it.orgId == device.orgId && it.pushToken == device.pushToken }
        val stored = existing?.copy(personId = device.personId, platform = device.platform, locale = device.locale)
            ?: device
        devices.remove(existing)
        devices += stored
        return stored
    }

    override suspend fun delete(orgId: Uuid, personId: String, id: Uuid): Boolean =
        devices.removeAll { it.orgId == orgId && it.personId == personId && it.id == id }
}

/** Expo, recording each batch. */
class RecordingPush : PushSender {
    val batches = mutableListOf<List<PushMessage>>()

    override suspend fun send(messages: List<PushMessage>) {
        batches += messages
    }
}

/** Resend, recording each email. */
class RecordingEmail(override val configured: Boolean = true) : EmailSender {
    val sent = mutableListOf<EmailMessage>()

    override suspend fun send(message: EmailMessage) {
        sent += message
    }
}

/** Addresses by member. */
class MapEmails(private val addresses: Map<String, String>) : PersonEmails {
    override suspend fun emailOf(orgId: Uuid, personId: String): String? = addresses[personId].takeIf { orgId == ORG }
}

/** A clock a test moves. */
class SettableClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now
}
