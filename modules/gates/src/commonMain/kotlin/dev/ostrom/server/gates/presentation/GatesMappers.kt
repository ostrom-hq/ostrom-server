package dev.ostrom.server.gates.presentation

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.InvalidGateRequestException
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.models.SelfApproval
import dev.ostrom.server.gates.domain.usecases.AskGate
import dev.ostrom.server.gates.domain.models.GatePush as DomainPush

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
    pullRequest = pr?.number?.trimStart('#')?.let {
        it.toIntOrNull() ?: throw InvalidGateRequestException("Malformed pull request number.")
    },
    diff = diff,
    via = AskedVia.HOOK,
)

/** Maps a gate for its caller: their own approval, when they are a person and an approver. */
internal fun GateView.toResponse(actor: GateActor): GateResponse {
    val mine = requests.takeIf { actor.kind == ActorKind.USER }?.firstOrNull { SelfApproval.same(it.approverId, actor.id) }
    return GateResponse(
        gate = gate.toRequest(mine?.id?.toString()),
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
    push = subject.push?.let { push -> GatePush(push.remote, push.refs.map { GatePushRef(it.ref, it.sha) }, push.force) },
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
        sha = gate.subject.pullRequest?.headSha ?: gate.subject.commits.lastOrNull(),
        commits = gate.subject.commits,
        requestedFrom = request.approverId,
        channel = request.channel?.name?.lowercase() ?: "console",
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
