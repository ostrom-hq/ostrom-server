// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.NodeRunOn
import run.ostrom.core.api.models.NodeSuggestions
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind

/**
 * What triggers a check (before the action) or a notify (after it).
 *
 * @property from `node` (declared on the node), `gate` (a linked gate's), `words` (the node's words), `suggested`
 *   (the analysis's, shadow only) or `none`.
 * @property gateNodeId The linked gate.
 * @property classes The action classes.
 * @property patterns Extra patterns.
 */
data class Trigger(val from: String, val gateNodeId: String?, val classes: List<String>, val patterns: List<String>)

/**
 * The suggestion fields of a node (`shared/src/suggest.ts`).
 *
 * @property wire The field's name in `dismissed`.
 */
enum class SuggestionField(val wire: String) {
    /** The check's command. */
    COMMAND("command"),

    /** The trigger's classes. */
    ON("on"),

    /** The gate's actions. */
    ACTIONS("actions"),

    /** The notification's channel. */
    CHANNEL("channel"),

    /** The linked rules. */
    RULE_IDS("ruleIds"),
}

/** The prototype's compilation table (`shared/src/compiler.ts` and `suggest.ts`), on the core model. */
object NodeRules {
    private const val TRIGGER_DEPTH = 6
    private val NO_SUGGESTIONS = NodeSuggestions()
    private val NO_RUN = NodeRun()
    private val NO_ON = NodeRunOn()

    private val wordClasses: List<Pair<Regex, String>> = listOf(
        Regex("\\bmerg", RegexOption.IGNORE_CASE) to "merge",
        Regex("\\bdeploy|\\bhelm\\b|\\brollout\\b", RegexOption.IGNORE_CASE) to "deploy",
        Regex("\\brelease|\\btag\\b", RegexOption.IGNORE_CASE) to "push-tag",
        Regex("\\bpush\\b.*\\b(main|master)\\b", RegexOption.IGNORE_CASE) to "push-main",
        Regex("\\bpush", RegexOption.IGNORE_CASE) to "push-any",
        Regex("\\bcommit", RegexOption.IGNORE_CASE) to "commit",
        Regex("\\bmigrat", RegexOption.IGNORE_CASE) to "migration",
    )

    /**
     * The node's suggestions, empty when it has none.
     *
     * @param node The node.
     * @return Its suggestions.
     */
    fun suggestions(node: WorkflowNode): NodeSuggestions = node.suggested ?: NO_SUGGESTIONS

    /**
     * The node's run settings, empty when it has none.
     *
     * @param node The node.
     * @return Its settings.
     */
    fun run(node: WorkflowNode): NodeRun = node.run ?: NO_RUN

    /**
     * Tells whether a node carries a suggestion to show: present, not dismissed, and the field has no human value
     * (for the rules: some suggested rule is not linked yet).
     *
     * @param node The node.
     * @param field The field.
     * @return True for an open suggestion.
     */
    fun openSuggestion(node: WorkflowNode, field: SuggestionField): Boolean {
        val suggested = suggestions(node)
        val present = when (field) {
            SuggestionField.COMMAND -> suggested.command != null
            SuggestionField.ON -> suggested.on != null
            SuggestionField.ACTIONS -> suggested.actions != null
            SuggestionField.CHANNEL -> suggested.channel != null
            SuggestionField.RULE_IDS -> suggested.ruleIds != null
        }
        val open = present && field.wire !in suggested.dismissed.orEmpty()
        return open && when (field) {
            SuggestionField.COMMAND -> run(node).command.orEmpty().isBlank()
            SuggestionField.CHANNEL -> run(node).channel.orEmpty().isBlank()
            SuggestionField.ON -> !declaresOn(node)
            SuggestionField.ACTIONS -> node.actions == null
            SuggestionField.RULE_IDS -> unlinkedRules(node) > 0
        }
    }

    internal fun unlinkedRules(node: WorkflowNode): Int =
        suggestions(node).ruleIds?.value.orEmpty().count { it !in node.ruleIds.orEmpty() }

    private fun declaresOn(node: WorkflowNode): Boolean =
        (run(node).on ?: NO_ON).let { it.classes.orEmpty().size + it.patterns.orEmpty().size > 0 }

    /**
     * The trigger of a check or a notify, through the graph: declared on the node, else a linked gate's (downstream
     * for a check, upstream for a notify, six steps at most), else the node's words.
     *
     * @param node The node.
     * @param workflow Its workflow.
     * @param suggested True to take the analysis's trigger right after a person's (shadow only).
     * @return The trigger.
     */
    fun triggerOf(node: WorkflowNode, workflow: Workflow, suggested: Boolean = false): Trigger {
        val on = run(node).on ?: NO_ON
        val suggestedOn = suggestions(node).on?.takeIf { suggested && openSuggestion(node, SuggestionField.ON) }?.value
        return when {
            declaresOn(node) -> Trigger("node", null, on.classes.orEmpty(), on.patterns.orEmpty())
            suggestedOn != null -> Trigger("suggested", null, suggestedOn, emptyList())
            else -> linkedGate(node, workflow) ?: byWords(node)
        }
    }

    private fun linkedGate(node: WorkflowNode, workflow: Workflow): Trigger? {
        val down = node.kind != WorkflowNodeKind.NOTIFY
        val seen = mutableSetOf(node.id)
        var frontier = listOf(node.id)
        var found: WorkflowNode? = null
        repeat(TRIGGER_DEPTH) {
            if (found == null) {
                frontier = frontier.flatMap { neighbours(workflow, it, down) }.filter { seen.add(it) }
                found = frontier.firstNotNullOfOrNull { id ->
                    workflow.nodes.firstOrNull { it.id == id && it.kind == WorkflowNodeKind.GATE }
                }
            }
        }
        return found?.let { gate ->
            val actions = gate.actions.orEmpty()
            Trigger("gate", gate.id, actions.map { it.cls }, actions.flatMap { it.patterns })
        }
    }

    private fun neighbours(workflow: Workflow, id: String, down: Boolean): List<String> =
        if (down) {
            workflow.edges.filter { it.from == id }.mapNotNull { it.to }
        } else {
            workflow.edges.filter { it.to == id }.map { it.from }
        }

    private fun byWords(node: WorkflowNode): Trigger {
        val text = "${node.label} ${node.detail.orEmpty()}"
        val hit = wordClasses.firstOrNull { (regex, _) -> regex.containsMatchIn(text) }
        return Trigger(if (hit == null) "none" else "words", null, listOfNotNull(hit?.second), emptyList())
    }
}
