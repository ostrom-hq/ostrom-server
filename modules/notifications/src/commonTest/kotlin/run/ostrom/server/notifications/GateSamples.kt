// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.notifications

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.models.RequestStatus
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val ASKED_AT = Instant.parse("2026-11-03T08:00:00Z")

/** A pending gate of [orgId] asked by [askedBy], on [repo]. */
fun sampleGate(
    orgId: Uuid,
    askedBy: List<String> = listOf("ana"),
    label: String = "Push to main",
    repo: String? = "acme/app",
) = Gate(
    id = Uuid.random(),
    orgId = orgId,
    key = "key",
    subject = GateSubject(repo = repo, actionClass = "push-main", command = "git push origin main", scope = "repo"),
    node = null,
    label = label,
    mode = GateMode.ENFORCED,
    status = GateStatus.PENDING,
    askedBy = askedBy,
    askedVia = setOf(AskedVia.HOOK),
    sessionIds = emptyList(),
    shownDigest = "digest",
    evidence = emptyList(),
    createdAt = ASKED_AT,
)

/** The request of [gate] to [approver]. */
fun sampleRequest(gate: Gate, approver: String, status: RequestStatus = RequestStatus.PENDING) =
    ApprovalRequest(Uuid.random(), gate.id, gate.orgId, approver, status, createdAt = ASKED_AT)
