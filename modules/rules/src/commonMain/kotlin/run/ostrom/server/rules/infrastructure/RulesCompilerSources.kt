// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.infrastructure

import run.ostrom.core.api.models.GateAction
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.RuleDoc
import run.ostrom.server.compiler.domain.services.CompilerSources
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.rules.domain.models.Rule
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.repositories.RulesRepository
import run.ostrom.server.rules.domain.services.EveryRule
import run.ostrom.server.rules.domain.services.RuleDocuments
import kotlin.uuid.Uuid

/**
 * The compiler's sources, from the organization's rules: every rule for the rules harness (it carries all but the
 * rejected ones, the decided first), the last decision of each for the receipts it cites, and each rule with a gate as
 * a one-gate workflow for the gates catalogue. An accepted or edited rule enforces its gate; a proposed one only
 * records the actions (shadow); a rejected one gates nothing. Repositories and settings stay the compiler's default.
 *
 * @param repository The rules.
 * @param defaults The compiler's own sources, for repositories and settings.
 */
class RulesCompilerSources(
    private val repository: RulesRepository,
    private val defaults: CompilerSources,
) : CompilerSources {
    override suspend fun rules(orgId: Uuid): List<RuleDoc> =
        EveryRule.of(repository, orgId).map { it.toDoc() }

    override suspend fun ruleDecisions(orgId: Uuid): List<DecisionDoc> =
        repository.lastRuleDecisions(orgId).map { decision ->
            DecisionDoc(
                id = decision.id,
                subjectKind = decision.subject.kind,
                subjectId = decision.subject.id,
                by = decision.by,
                at = decision.at.isoMillis(),
                // A receipt not signed yet is not cited: the harness names only what can be verified.
                receiptId = decision.receiptId.takeIf { decision.signed },
            )
        }

    override suspend fun repos(orgId: Uuid): List<String> = defaults.repos(orgId)

    override suspend fun settings(orgId: Uuid): CompilerSettings = defaults.settings(orgId)

    override suspend fun ruleGates(orgId: Uuid): List<Workflow> =
        EveryRule.gated(repository, orgId).mapNotNull { it.toGateWorkflow() }

    private fun Rule.toDoc(): RuleDoc = RuleDoc(
        raw = RuleDocuments.full(this),
        id = id,
        statement = statement,
        scope = scope,
        sourceRefs = sources.map { it.ref },
        status = status.value,
        strength = strength.value,
        confidence = confidence,
        group = group,
    )

    private fun Rule.toGateWorkflow(): Workflow? {
        val gate = gate ?: return null
        val label = statement.take(LABEL)
        return Workflow(
            id = "$WORKFLOW_PREFIX$id",
            name = label,
            domain = group,
            nodes = listOf(
                WorkflowNode(
                    id = NODE,
                    kind = WorkflowNodeKind.GATE,
                    label = label,
                    approvers = gate.approvers,
                    approverReason = gate.reason ?: if (gate.approvers.isEmpty()) null else REASON,
                    ruleIds = listOf(id),
                    actions = gate.actions.map { GateAction(it.cls, it.label, it.patterns, it.repos, it.tools) },
                ),
            ),
            edges = emptyList(),
            status = if (status == RuleStatus.PROPOSED) WorkflowStatus.PROPOSED else WorkflowStatus.ACCEPTED,
            foundAt = foundAt.isoMillis(),
            updatedAt = updatedAt.isoMillis(),
            updatedBy = decidedBy,
        )
    }

    /** How a rule's gate is named in the catalogue. */
    companion object {
        /** The workflow id of a rule's gate is this prefix and the rule id. */
        const val WORKFLOW_PREFIX: String = "rule:"

        /** The node id of a rule's gate. */
        const val NODE: String = "gate"

        private const val LABEL = 120
        private const val REASON = "approvers of the rule"
    }
}
