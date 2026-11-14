// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.infrastructure

import run.ostrom.core.api.models.GateAction
import run.ostrom.core.api.models.NodeSuggestions
import run.ostrom.core.api.models.RuleSource as ApiSource
import run.ostrom.core.api.models.StringSuggestion
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.models.Authorship
import run.ostrom.server.compiler.domain.repositories.MAX_WORKFLOWS
import run.ostrom.server.compiler.domain.repositories.WorkflowsRepository
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.rules.domain.models.LearnedWorkflow
import run.ostrom.server.rules.domain.models.ProcessNode
import run.ostrom.server.rules.domain.models.ProcessNodeKind
import run.ostrom.server.rules.domain.services.WorkflowProposals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WorkflowProposals] on the compiler's workflows. Each proposal passes the checks of a workflow the console saves
 * ([WorkflowsUseCase.check]), then is created only if its id is free ([WorkflowsRepository.saveWorkflow] without a
 * version), with no decision recorded: unlike the console's save, a proposal signs nothing. Its nodes follow one
 * another, the last ends it; its `repos` limit it to the repository it was found in.
 *
 * @param repository The compiler's workflows.
 * @param checks The compiler's workflow checks.
 */
class CompilerWorkflowProposals(
    private val repository: WorkflowsRepository,
    private val checks: WorkflowsUseCase,
) : WorkflowProposals {
    override suspend fun propose(orgId: Uuid, workflows: List<LearnedWorkflow>, at: Instant): Int {
        var count = repository.workflowCount(orgId)
        var stored = 0
        for (learned in workflows) {
            if (count >= MAX_WORKFLOWS) break
            val raw = learned.toRaw(at)
            checks.check(raw)
            if (repository.saveWorkflow(orgId, learned.id, raw, null, Authorship(FOUND_BY, at)) != null) {
                count++
                stored++
            }
        }
        return stored
    }

    private fun LearnedWorkflow.toRaw(at: Instant): JsonObject {
        val found = at.isoMillis()
        val nodes = proposal.nodes.map { it.toNode(found) }
        val workflow = Workflow(
            id = id,
            name = proposal.name,
            domain = DOMAIN,
            description = proposal.description,
            repos = listOf(proposal.repo),
            nodes = nodes,
            edges = nodes.mapIndexed { index, node -> WorkflowEdge(node.id, nodes.getOrNull(index + 1)?.id) },
            sources = proposal.sources.map { ApiSource(it.kind, it.ref, it.url) },
            status = WorkflowStatus.PROPOSED,
            foundAt = found,
            updatedAt = found,
        )
        return json.encodeToJsonElement(Workflow.serializer(), workflow).jsonObject
    }

    private fun ProcessNode.toNode(found: String): WorkflowNode {
        val gate = rule?.gate
        return WorkflowNode(
            id = id,
            kind = kinds.getValue(kind),
            label = label,
            approvers = gate?.approvers,
            approverReason = gate?.reason,
            ruleIds = rule?.let { listOf(it.id) },
            actions = gate?.actions?.map { GateAction(it.cls, it.label, it.patterns, it.repos, it.tools) },
            suggested = command?.let {
                NodeSuggestions(command = StringSuggestion(it.command, listOf(it.evidence), at = found))
            },
        )
    }

    /** The words of a learned workflow. */
    companion object {
        /** Who finds a learned workflow. */
        const val FOUND_BY: String = "analysis"

        /** The domain a learned workflow is listed under. */
        const val DOMAIN: String = "Processes"

        private val json = Json { encodeDefaults = false }
        private val kinds = mapOf(
            ProcessNodeKind.TRIGGER to WorkflowNodeKind.TRIGGER,
            ProcessNodeKind.STEP to WorkflowNodeKind.STEP,
            ProcessNodeKind.CHECK to WorkflowNodeKind.CHECK,
            ProcessNodeKind.GATE to WorkflowNodeKind.GATE,
            ProcessNodeKind.NOTIFY to WorkflowNodeKind.NOTIFY,
        )
    }
}
