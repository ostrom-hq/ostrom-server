// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.presentation

import dev.ostrom.server.compiler.domain.models.ApproverGate
import dev.ostrom.server.compiler.domain.models.ApproversGraph

/** Maps who approves what for its route. */
internal fun ApproversGraph.toResponse() = ApproversGraphResponse(
    approvers = approvers.map { line -> ApproverDto(line.approver, line.gates.map { it.toDto() }) },
    unanswered = unanswered.map { it.toDto() },
    ungated = ungated,
)

private fun ApproverGate.toDto() = ApproverGateDto(workflowId, workflowName, nodeId, label, enforce, actions)
