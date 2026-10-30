// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.integrations.slack.domain.SlackApi
import dev.ostrom.server.integrations.slack.domain.SlackCallException
import dev.ostrom.server.integrations.slack.domain.SlackLink
import dev.ostrom.server.integrations.slack.domain.SlackMessageRef
import dev.ostrom.server.integrations.slack.domain.SlackRepository
import dev.ostrom.server.integrations.slack.domain.SlackText
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
val OTHER_ORG: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
val NOW: Instant = Instant.parse("2026-10-30T09:00:00Z")
const val SHA = "0123456789abcdef0123456789abcdef01234567"

/** A pending push gate of [ORG], asked by `lyes`, with [diff] as the machine's diff. */
fun pushGate(
    diff: String? = "+ one line",
    force: Boolean = false,
    status: GateStatus = GateStatus.PENDING,
    push: Boolean = true,
): Gate = Gate(
    id = Uuid.parse("00000000-0000-0000-0000-0000000000a1"),
    orgId = ORG,
    key = "push",
    subject = GateSubject(
        repo = "acme/api",
        actionClass = "push-main",
        command = "git push origin main",
        scope = "session:s",
        push = GatePush("origin", listOf(PushRef("refs/heads/main", SHA)), force).takeIf { push },
    ),
    node = null,
    label = "Merge into main",
    mode = GateMode.ENFORCED,
    status = status,
    askedBy = listOf("lyes"),
    askedVia = setOf(AskedVia.HOOK),
    sessionIds = listOf("s"),
    shownDigest = "shown",
    evidence = listOfNotNull(
        Evidence("Command", "git push <origin> & main"),
        diff?.let { Evidence(Evidence.MACHINE_DIFF, it) },
    ),
    decidedBy = "rev-b".takeIf { status != GateStatus.PENDING },
    createdAt = NOW,
)

/** A request of [gate] for [approver]. */
fun requestOf(gate: Gate, approver: String, status: RequestStatus = RequestStatus.PENDING, n: Int = 1) =
    ApprovalRequest(
        id = Uuid.parse("00000000-0000-0000-0000-00000000000$n"),
        gateId = gate.id,
        orgId = gate.orgId,
        approverId = approver,
        status = status,
        createdAt = NOW,
    )

/** Slack as lists of what it was sent. */
class FakeSlackApi(override val configured: Boolean = true) : SlackApi {
    val opened = mutableListOf<String>()
    val posted = mutableListOf<Pair<String, SlackText>>()
    val updated = mutableListOf<Triple<String, String, SlackText>>()
    var failure: Exception? = null

    override suspend fun openDirectMessage(userId: String): String {
        failure?.let { throw it }
        opened += userId
        return "D-$userId"
    }

    override suspend fun post(channel: String, message: SlackText): String {
        posted += channel to message
        return "ts-${posted.size}"
    }

    override suspend fun update(channel: String, ts: String, message: SlackText) {
        failure?.let { throw it }
        updated += Triple(channel, ts, message)
    }
}

/** The repository in memory, with the uniqueness PostgreSQL enforces. */
class MemorySlackRepository : SlackRepository {
    val links = mutableListOf<SlackLink>()
    val messages = mutableListOf<SlackMessageRef>()

    override suspend fun links(orgId: Uuid): List<SlackLink> = links.filter { it.orgId == orgId }

    override suspend fun link(orgId: Uuid, personId: String): SlackLink? =
        links.firstOrNull { it.orgId == orgId && it.personId == personId }

    override suspend fun linkOf(teamId: String, userId: String): SlackLink? =
        links.firstOrNull { it.teamId == teamId && it.userId == userId }

    override suspend fun saveLink(link: SlackLink): Boolean {
        val taken = links.any { it.teamId == link.teamId && it.userId == link.userId && it.personId != link.personId }
        if (taken) return false
        links.removeAll { it.orgId == link.orgId && it.personId == link.personId }
        links += link
        return true
    }

    override suspend fun deleteLink(orgId: Uuid, personId: String): Boolean =
        links.removeAll { it.orgId == orgId && it.personId == personId }

    override suspend fun saveMessage(message: SlackMessageRef) {
        messages += message
    }

    override suspend fun messages(orgId: Uuid, gateId: Uuid): List<SlackMessageRef> =
        messages.filter { it.orgId == orgId && it.gateId == gateId }
}

/** A clock a test moves. */
class SettableClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now
}

/** A logger that keeps the level and message of each event. */
class RecordingLogger : Logger {
    val lines = mutableListOf<Pair<LogLevel, String>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        lines += level to message
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

/** A Slack failure, as the API client throws it. */
fun slackDown() = SlackCallException("Slack did not answer chat.update.")
