// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.server.kernel.events.GateSummaries
import run.ostrom.server.kernel.events.GateSummary
import run.ostrom.server.kernel.events.JournalEntry
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.notifications.domain.ApprovalDelivery
import run.ostrom.server.notifications.domain.ApprovalNotices
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val ASKED_AT = Instant.parse("2026-11-03T08:00:00Z")

/** A gate asked by [askedBy], on [repo], waiting for [pending]. */
fun sampleGate(
    askedBy: List<String> = listOf("ana"),
    label: String = "Push to main",
    repo: String? = "acme/app",
    pending: List<String> = listOf("bob"),
    open: Boolean = true,
) = GateSummary(Uuid.random().toString(), open, askedBy, label, repo, pending)

/** A `cloud.approval.requested` event, as the gates publish it. */
fun approvalRequested(seq: Long, approvalId: Uuid, gate: GateSummary, approver: String) = JournalEntry(
    seq,
    OrgEventType.APPROVAL_REQUESTED,
    approvalId.toString(),
    null,
    ASKED_AT,
    JsonObject(
        mapOf(
            "approvalId" to JsonPrimitive(approvalId.toString()),
            "gateId" to JsonPrimitive(gate.id),
            "approver" to JsonPrimitive(approver),
        ),
    ),
)

/** The delivery of [gate]'s request to [approver] on [channel]. */
fun approvalDelivery(
    orgId: Uuid,
    gate: GateSummary,
    approver: String = "bob",
    channel: String = "push",
    memberId: Uuid = Uuid.random(),
) = ApprovalDelivery(
    id = Uuid.random(),
    orgId = orgId,
    memberId = memberId,
    approver = approver,
    approvalId = Uuid.random(),
    gateId = Uuid.parse(gate.id),
    channel = channel,
    attempts = 0,
    eventSeq = 1,
    title = ApprovalNotices.title(gate),
    body = ApprovalNotices.body(gate),
    createdAt = ASKED_AT,
)

/**
 * The gates a test knows, as they are now; the ids asked are recorded.
 *
 * @param gates The gates.
 */
class MapGates(var gates: List<GateSummary> = emptyList()) : GateSummaries {
    val asked = mutableListOf<Set<String>>()

    override suspend fun of(orgId: Uuid, ids: Set<String>): List<GateSummary> {
        asked += ids
        return gates.filter { it.id in ids }
    }
}
