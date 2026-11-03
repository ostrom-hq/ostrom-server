// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.usecases.OpenGate
import dev.ostrom.server.gates.domain.usecases.OpenGateUseCase
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.workflowruns.domain.services.GateOpening
import dev.ostrom.server.workflowruns.domain.services.GateRequest
import dev.ostrom.server.workflowruns.domain.services.GateState
import dev.ostrom.server.workflowruns.domain.services.RunGates
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

    private companion object {
        const val ENGINE = "workflow-engine"
        const val ACTION_CLASS = "workflow_gate"
        const val TOOL = "workflow"
        const val MAX_LABEL = 200
        val DECIDED_YES = setOf(GateStatus.APPROVED, GateStatus.RESERVED, GateStatus.CONSUMED)
    }
}
