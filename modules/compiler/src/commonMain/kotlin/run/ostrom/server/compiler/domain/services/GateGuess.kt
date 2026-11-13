// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.server.compiler.domain.models.ActionClass
import run.ostrom.server.compiler.domain.services.GatesCatalog.Companion.aliases
import run.ostrom.server.compiler.domain.services.GatesCatalog.Companion.decided
import run.ostrom.server.compiler.domain.services.GatesCatalog.Companion.gates
import run.ostrom.server.compiler.domain.services.GatesCatalog.Companion.mentions

/**
 * The starting heuristic of the catalogue (`bridge/harness.ts`): among the gates that declared no action, the one
 * whose words speak of the action, in a workflow that names the repository.
 */
internal object GateGuess {
    private const val OWN_WORD = 2
    private const val ENOUGH_WORDS = 3
    private const val NAMES_REPO = 4

    /**
     * Guesses the gate of an action.
     *
     * @param action The action class.
     * @param repo The repository.
     * @param workflows The workflows.
     * @return The workflow and its gate, or null when no gate speaks of the action enough.
     */
    fun guess(action: ActionClass, repo: String?, workflows: List<Workflow>): Pair<Workflow, WorkflowNode>? {
        var best: Triple<Int, Workflow, WorkflowNode>? = null
        for (workflow in workflows) {
            val all = text(workflow)
            for (node in gates(workflow).filter { it.actions == null }) {
                val score = scoreOf(action, repo, workflow, all, node)
                val current = best
                if (score != null && (current == null || beats(score, workflow, current.first, current.second))) {
                    best = Triple(score, workflow, node)
                }
            }
        }
        return best?.let { it.second to it.third }
    }

    // Own words count twice, the workflow's once; null under three; a named repository and a decision add.
    private fun scoreOf(action: ActionClass, repo: String?, workflow: Workflow, all: String, node: WorkflowNode): Int? {
        val own = "${node.label} ${node.detail.orEmpty()}".lowercase()
        val words = action.keywords.sumOf { key ->
            when {
                word(own, key) -> OWN_WORD
                word(all, key) -> 1
                else -> 0
            }
        }
        val named = repo?.let { r -> aliases(r).any { mentions(all, it) } } == true
        val score = words + (if (named) NAMES_REPO else 0) + (if (decided(workflow)) 1 else 0)
        return score.takeIf { words >= ENOUGH_WORDS }
    }

    // A higher score wins; at equal scores, a decided workflow first, then the lower id.
    private fun beats(score: Int, workflow: Workflow, bestScore: Int, best: Workflow): Boolean {
        val first = decided(workflow) && !decided(best)
        val same = decided(workflow) == decided(best) && workflow.id < best.id
        return score > bestScore || score == bestScore && (first || same)
    }

    private fun word(text: String, key: String): Boolean =
        Regex("\\b${Regex.escape(key)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun text(workflow: Workflow): String =
        (listOf(workflow.name, workflow.domain) + workflow.nodes.map { "${it.label} ${it.detail.orEmpty()}" })
            .joinToString(" ")
            .lowercase()
}
