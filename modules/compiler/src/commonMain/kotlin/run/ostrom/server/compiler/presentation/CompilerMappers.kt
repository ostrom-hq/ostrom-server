// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.presentation

import run.ostrom.server.compiler.domain.models.ApproverGate
import run.ostrom.server.compiler.domain.models.ApproversGraph
import run.ostrom.server.compiler.domain.models.PackageAudit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

/** Maps who approves what for its route. */
internal fun ApproversGraph.toResponse() = ApproversGraphResponse(
    approvers = approvers.map { line -> ApproverDto(line.approver, line.gates.map { it.toDto() }) },
    unanswered = unanswered.map { it.toDto() },
    ungated = ungated,
)

private fun ApproverGate.toDto() = ApproverGateDto(workflowId, workflowName, nodeId, label, enforce, actions)

/** Maps an audit line for its route: every meta value a string (`AuditEvent.meta`), a null one left out. */
internal fun PackageAudit.toAuditEvent() = AuditEvent(
    id = id,
    at = at,
    event = event,
    actor = actor,
    subject = subject,
    meta = meta.filterValues { it != JsonNull }.mapValues { (_, value) -> value.jsonPrimitive.content },
)
