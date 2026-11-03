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
     * The repositories the gates' declared actions are limited to: the organization's catalogue leaves out a gate
     * limited to them, theirs has it.
     *
     * @return The repositories, as first written (case ignored).
     */
    fun repositories(): List<String> = workflows
        .flatMap { workflow -> gates(workflow).flatMap { it.actions.orEmpty() } }
        .flatMap { it.repos }
        .distinctBy { it.lowercase() }

    /**
     * The gate that covers an action class in a repository.
     *
     * @param action The action class.
     * @param repo The repository.
     * @return The gate, or null.
     */
    fun gateFor(action: ActionClass, repo: String?): CatalogGate? {
        val declared = declaredGate(action, repo)
        val guessed = GateGuess.guess(action, repo, workflows)
        // Second audit H2: proposing never removes a gate. A decided gate, declared or guessed, comes before any
        // proposed one: a proposed workflow that only declares the class never turns an enforced gate into a shadow.
        val chosen = when {
            declared != null && decided(declared.first) -> Triple(declared.first, declared.second, "declared")
            guessed != null && decided(guessed.first) -> Triple(guessed.first, guessed.second, "guessed")
            declared != null -> Triple(declared.first, declared.second, "declared")
            else -> guessed?.let { Triple(it.first, it.second, "guessed") }
        }
        return chosen?.let { (workflow, node, basis) -> ref(workflow, node, basis) }
    }

    // The gate that declares the class, decided first, then the one that names the repository; the first of equals.
    private fun declaredGate(action: ActionClass, repo: String?): Pair<Workflow, WorkflowNode>? {
        var declared: Triple<Int, Workflow, WorkflowNode>? = null
        for (workflow in workflows) {
            for (node in gates(workflow)) {
                val actions = node.actions ?: continue
                val covered = actions.firstOrNull { it.cls == action.cls && covers(it.repos, repo) }
                val score = decidedScore(workflow) + if (covered?.repos.orEmpty().isNotEmpty()) 1 else 0
                if (covered != null && (declared == null || score > declared.first)) {
                    declared = Triple(score, workflow, node)
                }
            }
        }
        return declared?.let { it.second to it.third }
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

    // Second audit H2: the patterns a decided gate declares replace the built-in ones; a proposed workflow's only add
    // to them. A proposal that matched nothing (`a^`) would otherwise let the action through every hook.
    private fun patternsFor(action: ActionClass, repo: String?): List<String> {
        val declared = workflows.flatMap { workflow ->
            gates(workflow).mapNotNull { node ->
                node.actions.orEmpty().firstOrNull { it.cls == action.cls && covers(it.repos, repo) }
                    ?.takeIf { it.patterns.isNotEmpty() }
                    ?.let { decided(workflow) to it.patterns }
            }
        }
        val (decided, proposed) = declared.partition { it.first }
        // The first decided gate's patterns, else the built-in ones; then every proposal's.
        val base = (decided.map { it.second } + listOf(action.patterns)).first()
        return (base + proposed.flatMap { it.second }).distinct()
    }

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
