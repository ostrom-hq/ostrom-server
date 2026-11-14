// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/**
 * The event of [type] about this request, as [OrgEventType] documents it: `{approvalId, gateId}`, and the gate's
 * repository when it is asked or its `status` (`approved`, `rejected`) when it is answered. It belongs to the
 * approver's [member]. Never the gate's label (the asker's text), its evidence or the asker.
 */
internal fun ApprovalRequest.event(type: OrgEventType, gate: Gate, member: Uuid): OrgEventDraft {
    val data = buildMap {
        put("approvalId", JsonPrimitive(id.toString()))
        put("gateId", JsonPrimitive(gateId.toString()))
        if (type == OrgEventType.APPROVAL_DECIDED) {
            put("status", JsonPrimitive(gate.status.name.lowercase()))
        } else {
            gate.subject.repo?.let { put("repo", JsonPrimitive(it)) }
        }
    }
    return OrgEventDraft(type, id.toString(), member, JsonObject(data))
}
