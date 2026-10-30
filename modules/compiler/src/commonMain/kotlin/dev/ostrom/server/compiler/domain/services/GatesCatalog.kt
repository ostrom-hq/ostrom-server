// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.services

import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowNodeKind
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.ActionClass
import dev.ostrom.server.compiler.domain.models.ActionClasses
import dev.ostrom.server.compiler.domain.models.CatalogEntry
import dev.ostrom.server.compiler.domain.models.CatalogGate

/**
 * Which gate covers which boundary action in a repository (`bridge/harness.ts`): what the gates declare first
 * (decided before proposed, a named repository before all), else a guess from the gate's words, among the gates
 * that declared nothing.
 *
 * @param workflows The organization's workflows.
 * @param prodApprover The approver of a gate that names none.
 */
class GatesCatalog(private val workflows: List<Workflow>, private val prodApprover: String?) {
    /**
     * The catalogue of a repository: the built-in classes then the custom ones the gates declare, each with its
     * patterns for this repository and its gate.
     *
     * @param repo The repository, or null for the organization.
     * @return The catalogue, in order.
     */
    fun catalog(repo: String?): List<CatalogEntry> = (ActionClasses.builtIn + customActions()).map { action ->
        CatalogEntry(action.copy(patterns = patternsFor(action, repo)), gateFor(action, repo))
    }

    /**
     * The gate that covers an action class in a repository.
     *
     * @param action The action class.
     * @param repo The repository.
     * @return The gate, or null.
     */
    fun gateFor(action: ActionClass, repo: String?): CatalogGate? {
        var declared: Triple<Int, Workflow, WorkflowNode>? = null
        for (workflow in workflows) {
            for (node in gates(workflow).filter { it.actions != null }) {
                val covered = node.actions.orEmpty().firstOrNull { it.cls == action.cls && covers(it.repos, repo) }
                val score = decidedScore(workflow) + if (covered?.repos.orEmpty().isNotEmpty()) 1 else 0
                if (covered != null && (declared == null || score > declared.first)) {
                    declared = Triple(score, workflow, node)
                }
            }
        }
        val chosen = declared?.let { Triple(it.second, it.third, "declared") }
            ?: GateGuess.guess(action, repo, workflows)?.let { Triple(it.first, it.second, "guessed") }
        return chosen?.let { (workflow, node, basis) -> ref(workflow, node, basis) }
    }

    private fun decidedScore(workflow: Workflow): Int = if (decided(workflow)) 2 else 0

    private fun ref(workflow: Workflow, node: WorkflowNode, basis: String): CatalogGate {
        val named = node.approvers.orEmpty()
        val approvers = named.ifEmpty { listOfNotNull(prodApprover) }
        val reason = node.approverReason
            ?: if (named.isNotEmpty()) "gate approvers of the workflow" else "production approver in Settings"
        return CatalogGate(
            workflowId = workflow.id,
            workflowName = workflow.name,
            nodeId = node.id,
            label = node.label,
            approvers = approvers,
            status = workflow.status.name.lowercase(),
            enforce = decided(workflow),
            basis = basis,
            reason = reason,
        )
    }

    private fun customActions(): List<ActionClass> {
        val out = linkedMapOf<String, ActionClass>()
        val declared = workflows.flatMap { workflow -> gates(workflow).flatMap { it.actions.orEmpty() } }
        for (action in declared) {
            val known = ActionClasses.builtIn.any { it.cls == action.cls } || action.cls in out
            if (!known && action.patterns.isNotEmpty()) {
                val tools = action.tools.orEmpty().ifEmpty { listOf("Bash") }
                val label = action.label.ifEmpty { action.cls }
                out[action.cls] = ActionClass(action.cls, label, tools, action.patterns, emptyList())
            }
        }
        return out.values.toList()
    }

    private fun patternsFor(action: ActionClass, repo: String?): List<String> = workflows.asSequence()
        .flatMap { gates(it) }
        .mapNotNull { node -> node.actions.orEmpty().firstOrNull { it.cls == action.cls && covers(it.repos, repo) } }
        .firstOrNull { it.patterns.isNotEmpty() }
        ?.patterns
        ?: action.patterns

    /** Matching helpers of the prototype, shared with the rules harness. */
    companion object {
        private const val MIN_ALIAS = 3

        /**
         * The short names of a repository: `controlresell-core` → `controlresell-core`, `core`.
         *
         * @param repo The repository.
         * @return Its names of three characters or more.
         */
        fun aliases(repo: String): List<String> {
            val lower = repo.lowercase()
            val parts = lower.split('-')
            val short = if (parts.size > 1) parts.drop(1).joinToString("-") else lower
            return listOf(lower, short).distinct().filter { it.length >= MIN_ALIAS }
        }

        /**
         * Tells whether a text names an alias as a whole word (letters, digits and `-` are part of a name).
         *
         * @param text The text.
         * @param alias The name.
         * @return True when named.
         */
        fun mentions(text: String, alias: String): Boolean =
            Regex("(^|[^a-z0-9-])${Regex.escape(alias)}([^a-z0-9-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)

        /**
         * Tells whether a declared action's repositories cover a repository (`[]` covers all).
         *
         * @param repos The declared repositories.
         * @param repo The session's repository.
         * @return True when covered.
         */
        fun covers(repos: List<String>, repo: String?): Boolean {
            val names = repo?.let { aliases(it) + it.lowercase() }.orEmpty()
            return repos.isEmpty() || repos.any { it.lowercase() in names }
        }

        internal fun decided(workflow: Workflow): Boolean = workflow.status != WorkflowStatus.PROPOSED

        internal fun gates(workflow: Workflow): List<WorkflowNode> =
            workflow.nodes.filter { it.kind == WorkflowNodeKind.GATE }
    }
}
