// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Evidence
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.usecases.OpenGate
import run.ostrom.server.gates.domain.usecases.OpenGateUseCase
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.workflowruns.domain.services.GateOpening
import run.ostrom.server.workflowruns.domain.services.GateRequest
import run.ostrom.server.workflowruns.domain.services.GateState
import run.ostrom.server.workflowruns.domain.services.RunGates
import kotlin.uuid.Uuid

/**
 * [RunGates] on the gates module: a gate node's gate is opened by the same use case as a workstation's ask, enforced,
 * waiting for the node's approvers, asked for the run's starter. Its subject is the visit (`run:<id>:<seq>`), so the
 * gate of a visit asked again (after a restart) is the one already opened, and two visits never share a yes.
 *
 * @param open Opens or joins a gate.
 * @param gates The gates.
 */
class GatesRunGates(private val open: OpenGateUseCase, private val gates: GatesRepository) : RunGates {
    override suspend fun open(orgId: Uuid, request: GateRequest): GateOpening = try {
        GateOpening(gateId = open(ask(orgId, request)).gate.id)
    } catch (refused: OstromException) {
        GateOpening(refusal = refused.message)
    }

    override suspend fun state(orgId: Uuid, gateId: Uuid): GateState? = gates.get(orgId, gateId)?.let {
        when (it.status) {
            GateStatus.REJECTED -> GateState.REJECTED
            in DECIDED_YES -> GateState.APPROVED
            else -> GateState.PENDING
        }
    }

    private fun ask(orgId: Uuid, request: GateRequest): OpenGate {
        val node = GateNode(
            workflowId = request.workflowId,
            nodeId = request.nodeId,
            name = request.label.take(MAX_LABEL),
            approvers = request.approvers,
            enforced = true,
            workflowName = request.workflowName,
        )
        val subject = GateSubject(
            repo = null,
            actionClass = ACTION_CLASS,
            command = "${request.workflowName} · ${request.label}".take(MAX_LABEL),
            scope = "run:${request.runId}:${request.seq}",
            tool = TOOL,
            runId = request.runId,
        )
        return OpenGate(
            actor = GateActor(ENGINE, orgId, ActorKind.SERVICE, personId = request.asker),
            asker = request.asker,
            via = AskedVia.WORKFLOW,
            sessionId = null,
            subject = subject,
            node = node,
            label = request.label.take(MAX_LABEL),
            evidence = listOf(
                Evidence("Workflow", request.workflowName.take(MAX_LABEL)),
                Evidence("Run", request.runId),
                Evidence("Started by", request.asker),
            ),
        )
    }
}

// Who asks (the server's engine), what kind of action a gate node covers, and the longest label a gate takes.
private const val ENGINE = "workflow-engine"
private const val ACTION_CLASS = "workflow_gate"
private const val TOOL = "workflow"
private const val MAX_LABEL = 200

// A yes, used or not: the gate node said its part once an approver said yes.
private val DECIDED_YES = setOf(GateStatus.APPROVED, GateStatus.RESERVED, GateStatus.CONSUMED)
