// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.fakes

import dev.ostrom.core.api.models.CheckAttestation
import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.Notification
import dev.ostrom.core.api.models.NotificationStatus
import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.core.api.models.PackageMode
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.gates.domain.usecases.SessionGateDecision
import dev.ostrom.server.receipts.domain.models.ReceiptQuery
import dev.ostrom.server.receipts.domain.models.StoredReceipt
import dev.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.workflowruns.domain.models.PackageNode
import dev.ostrom.server.workflowruns.domain.models.PackageNotify
import dev.ostrom.server.workflowruns.domain.models.PackageScript
import dev.ostrom.server.workflowruns.domain.models.RunPackage
import dev.ostrom.server.workflowruns.domain.repositories.RunQuery
import dev.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import dev.ostrom.server.workflowruns.domain.services.Delivery
import dev.ostrom.server.workflowruns.domain.services.NotificationChannels
import dev.ostrom.server.workflowruns.domain.services.RunLaunches
import dev.ostrom.server.workflowruns.domain.services.RunPackages
import dev.ostrom.server.workflowruns.domain.services.RunSessions
import dev.ostrom.server.workflowruns.domain.usecases.RunAccess
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject

val ORG: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
val STARTED: Instant = Instant.parse("2026-10-30T10:00:00Z")

/** Ada's agent token on launch `lch_1`, Ada herself, Bob, and an agent of another launch. */
val AGENT: GateActor = GateActor("agent-lch_1", ORG, ActorKind.AGENT_SESSION, launchId = "lch_1", personId = "ada")
val ADA: GateActor = GateActor("ada", ORG, ActorKind.USER, personId = "ada")
val BOB: GateActor = GateActor("bob", ORG, ActorKind.USER, personId = "bob")
val OTHER_AGENT: GateActor =
    GateActor("agent-lch_2", ORG, ActorKind.AGENT_SESSION, launchId = "lch_2", personId = "ada")

fun session(
    id: String = "s-1",
    receiptId: String? = null,
    ended: Boolean = false,
    packageDigest: String? = null,
    repo: String? = "acme/api",
) = Session(
    orgId = ORG,
    id = id,
    rootId = id,
    segment = 1,
    launchId = "lch_1",
    repo = repo,
    agent = "claude-code",
    dev = "ada",
    startedAt = STARTED,
    endedAt = STARTED.takeIf { ended },
    packageDigest = packageDigest,
    receiptId = receiptId,
)

fun envelope(revoked: Boolean = false, repo: String? = "acme/pkg") = PackageEnvelope(
    format = "ostrom.package/1",
    version = "v3",
    repo = repo,
    digest = "sha256:pkg",
    contentDigest = "sha256:content",
    alg = "Ed25519",
    kid = "k1",
    signature = "sig",
    payload = "{}",
    pinned = true,
    revoked = revoked,
)

fun node(id: String, kind: String, workflow: String = "wf1", mode: PackageMode = PackageMode.ENFORCE) =
    PackageNode(id, workflow, "Ship $workflow", kind, "Label $id", mode)

val PACKAGE: RunPackage = RunPackage(
    envelope(),
    nodes = listOf(node("n-check", "check"), node("n-notify", "notify")),
    scripts = listOf(
        PackageScript("sc-1", "n-check", "sha-good", PackageMode.ENFORCE),
        PackageScript("sc-2", "n-check", "sha-shadow", PackageMode.SHADOW),
    ),
    notifications = listOf(
        PackageNotify("nt-1", "n-notify", "log:team", "{dev} ran {command} in {repo} ({session})", PackageMode.ENFORCE),
        PackageNotify("nt-2", "n-notify", "slack:#ops", "to slack", PackageMode.ENFORCE),
        PackageNotify("nt-3", "n-notify", "log:team", "shadow {dev}", PackageMode.SHADOW),
    ),
)

class FixedClock(var now: Instant = Instant.parse("2026-10-30T10:05:00.123456Z")) : Clock {
    override fun now(): Instant = now
}

class FakeSessions(
    private val sessions: List<Session> = listOf(session()),
    private val gates: List<SessionGate> = emptyList(),
) : RunSessions {
    override suspend fun find(orgId: Uuid, id: String): Session? =
        sessions.firstOrNull { it.orgId == orgId && it.id == id }

    override suspend fun gates(orgId: Uuid, id: String): List<SessionGate> = gates
}

class FakePackages(private val packages: List<RunPackage> = listOf(PACKAGE)) : RunPackages {
    override suspend fun find(orgId: Uuid, digest: String): RunPackage? = packages.firstOrNull { it.digest == digest }
}

fun access(sessions: RunSessions = FakeSessions(), packages: RunPackages = FakePackages()) =
    RunAccess(sessions, packages)

/** The repository in memory, with the database's rule of one notification per action of a session. */
class InMemoryRuns : WorkflowRunsRepository {
    val checks = mutableListOf<CheckRun>()
    val attested = mutableMapOf<String, CheckAttestation>()
    val notifications = mutableListOf<Pair<Notification, String>>()
    val queries = mutableListOf<RunQuery>()

    override suspend fun addCheck(orgId: Uuid, run: CheckRun, attestation: CheckAttestation) {
        checks += run
        if (attestation != CheckAttestation.WORKSTATION_DECLARED) attested[run.id] = attestation
    }

    override suspend fun attestations(orgId: Uuid, sessionId: String): Map<String, CheckAttestation> =
        attested.filterKeys { id -> checks.any { it.id == id && it.sessionId == sessionId } }

    override suspend fun checks(orgId: Uuid, query: RunQuery): List<CheckRun> {
        queries += query
        return checks.filter { query.sessionId == null || it.sessionId == query.sessionId }.reversed()
    }

    override suspend fun addNotification(orgId: Uuid, notification: Notification, actionKey: String): Notification {
        val first = notifications.firstOrNull { (stored, key) ->
            stored.sessionId != null && stored.sessionId == notification.sessionId &&
                stored.notifyId == notification.notifyId && key == actionKey &&
                stored.status != NotificationStatus.NOT_SENT
        }
        if (first != null) return first.first
        notifications += notification to actionKey
        return notification
    }

    override suspend fun updateNotification(orgId: Uuid, notification: Notification) {
        val index = notifications.indexOfFirst { it.first.id == notification.id }
        notifications[index] = notification to notifications[index].second
    }

    override suspend fun notifications(orgId: Uuid, query: RunQuery): List<Notification> {
        queries += query
        return notifications.map { it.first }.filter { query.sessionId == null || it.sessionId == query.sessionId }
            .reversed()
    }
}

/** The cloud session that opened each launch of [opened]; any other launch is the CLI's. */
class FakeLaunches(private val opened: Map<String, Uuid> = emptyMap()) : RunLaunches {
    override suspend fun cloudSession(orgId: Uuid, launchId: String): Uuid? = opened[launchId]
}

class RecordingChannels : NotificationChannels {
    val delivered = mutableListOf<Pair<String, String>>()

    override suspend fun deliver(orgId: Uuid, channel: String, text: String): Delivery {
        delivered += channel to text
        return if (channel.startsWith("log:")) {
            Delivery(NotificationStatus.SENT)
        } else {
            Delivery(NotificationStatus.NOT_SENT, "not connected")
        }
    }
}

fun gate(approved: Boolean, self: Boolean, receiptId: String? = "rcpt_gate_1") = SessionGate(
    id = Uuid.random(),
    actionClass = "deploy",
    mode = GateMode.ENFORCED,
    status = if (approved) GateStatus.APPROVED else GateStatus.REJECTED,
    receiptId = receiptId,
    decisions = listOf(
        SessionGateDecision(Uuid.parse("000000a1-0000-0000-0000-000000000000"), "lyes", STARTED, approved, self),
    ),
)

class InMemoryReceipts(vararg receipts: Pair<String, JsonObject>) : ReceiptsRepository {
    private val stored = receipts.associate { (id, body) -> id to StoredReceipt(ORG, id, "x", "x", body, STARTED) }

    override suspend fun insertIfAbsent(receipt: StoredReceipt): StoredReceipt = error("Bundles sign nothing.")

    override suspend fun get(orgId: Uuid, id: String): StoredReceipt? = stored[id]?.takeIf { it.orgId == orgId }

    override suspend fun list(orgId: Uuid, query: ReceiptQuery): List<StoredReceipt> = error("Bundles list nothing.")
}
