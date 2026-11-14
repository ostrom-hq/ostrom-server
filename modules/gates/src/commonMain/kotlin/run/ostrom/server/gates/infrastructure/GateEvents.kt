// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The event of [type] about this request, as [OrgEventType] documents it: `{approvalId, gateId, approver}`, and the
 * gate's repository when it is asked or the request's own `status` when the gate is answered (`approved` or
 * `rejected` for the answerer's, `closed` for the others). An event of the whole organization: an approver is a login,
 * which the gates do not turn into a member inside their transaction. Never the gate's label (the asker's text), its
 * evidence or the asker.
 */
internal fun ApprovalRequest.event(type: OrgEventType, gate: Gate): OrgEventDraft {
    val data = buildMap {
        put("approvalId", JsonPrimitive(id.toString()))
        put("gateId", JsonPrimitive(gateId.toString()))
        put("approver", JsonPrimitive(approverId))
        if (type == OrgEventType.APPROVAL_DECIDED) {
            put("status", JsonPrimitive(status.name.lowercase()))
        } else {
            gate.subject.repo?.let { put("repo", JsonPrimitive(it)) }
        }
    }
    return OrgEventDraft(type, id.toString(), null, JsonObject(data))
}
