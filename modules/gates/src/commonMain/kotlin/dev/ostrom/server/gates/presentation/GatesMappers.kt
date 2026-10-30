// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.presentation

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePush as DomainPush
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.InvalidGateRequestException
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.models.SelfApproval
import dev.ostrom.server.gates.domain.usecases.AskGate

/** Maps the hook's request; `dev` is ignored, the asker is the credential's person. */
internal fun GateAskRequest.toAskGate() = AskGate(
    sessionId = sessionId,
    runId = runId,
    repo = repo,
    realRepo = realRepo,
    actionClass = cls,
    tool = tool,
    command = command.orEmpty(),
    harnessVersion = harnessVersion,
    push = push?.let { dto -> DomainPush(dto.remote, dto.refs.map { PushRef(it.ref, it.sha) }, dto.force == true) },
    pullRequest = pullRequestNumber(if (pr == null) ci?.pr else pr.number),
    diff = diff,
    // The GitHub check sends its CI context, often with its pull request there only.
    via = if (ci == null) AskedVia.HOOK else AskedVia.CI,
)

private fun pullRequestNumber(number: String?): Int? = number?.let {
    it.trimStart('#').toIntOrNull() ?: throw InvalidGateRequestException("Malformed pull request number.")
}

/** Maps a gate for its caller: their own approval, when they are a person and an approver. */
internal fun GateView.toResponse(actor: GateActor): GateResponse {
    val mine = requests.takeIf { actor.kind == ActorKind.USER }
        ?.firstOrNull { SelfApproval.same(it.approverId, actor.id) }
    return GateResponse(
        gate = gate.toRequest(mine?.let { it.id.toString() }),
        approval = mine?.let { ApprovalView(it, gate).toApproval() },
        waitingFor = waitingFor,
    )
}

/** Maps a gate to the contract's gate request. A reserved yes reads `approved`: the contract has no reserved state. */
internal fun Gate.toRequest(approvalId: String?) = GateRequest(
    id = id.toString(),
    approvalId = approvalId,
    sessionId = sessionIds.firstOrNull(),
    runId = subject.runId,
    repo = subject.repo,
    realRepo = subject.realRepo,
    dev = askedBy.firstOrNull().orEmpty(),
    cls = subject.actionClass,
    label = label,
    tool = subject.tool,
    command = subject.command,
    key = key,
    gate = node?.toRef(),
    mode = mode.name.lowercase(),
    status = if (status == GateStatus.RESERVED) "approved" else status.name.lowercase(),
    at = createdAt.toString(),
    decidedBy = decidedBy,
    decidedAt = decidedAt?.toString(),
    receiptId = receiptId,
    harnessVersion = subject.harnessVersion,
    ci = subject.pullRequest?.takeIf { AskedVia.CI in askedVia }?.let { GateCi(it.headSha, it.number.toString()) },
    devVerified = true,
    pr = subject.pullRequest?.let { GatePullRequest(it.number.toString(), it.headSha, it.base) },
    askedVia = askedVia.map { it.name.lowercase() }.sorted(),
    selfApproved = selfApproved,
    push = subject.push?.let { push ->
        GatePush(push.remote, push.refs.map { GatePushRef(it.ref, it.sha) }, push.force)
    },
)

private fun GateNode.toRef() = GateRef(
    workflowId = workflowId,
    workflowName = workflowName,
    nodeId = nodeId,
    label = name,
    approvers = approvers,
    status = workflowStatus,
    enforce = enforced,
    basis = basis,
    reason = reason,
)

/** Maps one approver's request; a request closed by another approver's answer reads `expired`. */
internal fun ApprovalView.toApproval(): Approval {
    val where = (gate.subject.realRepo ?: gate.subject.repo)?.let { " in $it" }.orEmpty()
    return Approval(
        id = request.id.toString(),
        group = gate.id.toString(),
        workflowId = gate.node?.workflowId,
        title = "${gate.askedBy.firstOrNull() ?: "An agent"}: ${gate.label}$where",
        evidence = gate.evidence.map { Evidence(it.label, it.value) },
        repo = gate.subject.repo,
        sha = shaOf(gate),
        commits = gate.subject.commits,
        requestedFrom = request.approverId,
        channel = channelOf(request.channel),
        status = when (request.status) {
            RequestStatus.CLOSED -> "expired"
            else -> request.status.name.lowercase()
        },
        decidedBy = gate.decidedBy,
        decidedAt = gate.decidedAt?.toString(),
        shownDigest = gate.shownDigest,
        askedBy = gate.askedBy,
        selfApproved = gate.selfApproved,
    )
}

// The head of a pull request, else the last commit of a push. An if, not `?.headSha ?:`: Kover counts the
// null check of a property that is never null.
private fun shaOf(gate: Gate): String? {
    val pullRequest = gate.subject.pullRequest
    return if (pullRequest == null) gate.subject.commits.lastOrNull() else pullRequest.headSha
}

private fun channelOf(channel: AnswerChannel?): String = if (channel == null) "console" else channel.name.lowercase()
