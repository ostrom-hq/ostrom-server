package dev.ostrom.server.compiler.domain.services

import dev.ostrom.core.api.models.AgentEvent
import dev.ostrom.core.api.models.ArtifactType
import dev.ostrom.core.api.models.NodeRun
import dev.ostrom.core.api.models.NodeRunOn
import dev.ostrom.core.api.models.NodeSuggestions
import dev.ostrom.core.api.models.Produced
import dev.ostrom.core.api.models.RunLocation
import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowNodeKind
import dev.ostrom.core.api.models.WorkflowStatus

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

    private fun unlinkedRules(node: WorkflowNode): Int =
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
        val suggestedOn = suggestions(node).on?.value?.takeIf { suggested && openSuggestion(node, SuggestionField.ON) }
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

    /**
     * What a node produces in a session, given its kind and settings.
     *
     * @param node The node.
     * @param workflow Its workflow.
     * @return The artifacts, in the prototype's order and wording.
     */
    fun produces(node: WorkflowNode, workflow: Workflow): List<Produced> {
        val shadow = workflow.status == WorkflowStatus.PROPOSED
        val unlinked = unlinkedRules(node).takeIf { openSuggestion(node, SuggestionField.RULE_IDS) }
        val rulesNote = unlinked?.let {
            "$it rule${if (it > 1) "s" else ""} suggested by the analysis: not in the skill until someone links them"
        }
        return when (node.kind) {
            WorkflowNodeKind.TRIGGER -> listOf(
                Produced(
                    ArtifactType.CONTEXT,
                    event = AgentEvent.SESSION_START,
                    note = "when the workflow starts: one line of the session context",
                ),
            )
            WorkflowNodeKind.STEP -> listOf(
                Produced(ArtifactType.SKILL, note = rulesNote ?: STEP_NOTE),
            )
            WorkflowNodeKind.CHECK -> checkProduces(node, workflow, shadow, rulesNote)
            WorkflowNodeKind.GATE -> gateProduces(node, shadow)
            WorkflowNodeKind.NOTIFY -> notifyProduces(node, workflow, shadow)
            WorkflowNodeKind.AGENT -> listOf(
                Produced(ArtifactType.SUBAGENT, note = rulesNote ?: AGENT_NOTE),
                Produced(ArtifactType.MCP, note = "node_done: the agent reports the review, kept in the receipt"),
            )
            else -> emptyList()
        }
    }

    // The note of a hook without a trigger: the analysis's suggestion when there is one, else [fallback].
    private fun triggerNote(node: WorkflowNode, workflow: Workflow, fallback: String): String? {
        val none = triggerOf(node, workflow).from == "none"
        val suggested = suggestions(node).on?.value?.takeIf { openSuggestion(node, SuggestionField.ON) }
        val note = suggested?.let {
            "no trigger set: the analysis suggests ${it.joinToString(", ")}, used only once someone takes it"
        }
        return if (none) note ?: fallback else null
    }

    private fun checkProduces(
        node: WorkflowNode,
        workflow: Workflow,
        shadow: Boolean,
        rulesNote: String?,
    ): List<Produced> {
        val command = run(node).command.orEmpty()
        return when {
            command.isEmpty() && openSuggestion(node, SuggestionField.COMMAND) -> {
                val found = triggerOf(node, workflow, suggested = true).from != "none"
                listOf(
                    Produced(ArtifactType.SKILL, note = SUGGESTED_COMMAND_NOTE),
                    Produced(
                        ArtifactType.HOOK,
                        event = AgentEvent.PRE_TOOL_USE,
                        shadow = true,
                        suggested = true,
                        note = if (found) {
                            "suggested command: logged, never blocking"
                        } else {
                            "suggested command, no trigger found"
                        },
                    ),
                    Produced(
                        ArtifactType.SCRIPT,
                        where = RunLocation.WORKSTATION,
                        shadow = true,
                        suggested = true,
                        note = "suggested by the analysis: never run until someone uses it",
                    ),
                )
            }
            command.isEmpty() -> listOf(
                Produced(ArtifactType.SKILL, note = rulesNote ?: NO_COMMAND_NOTE),
            )
            else -> listOf(
                Produced(
                    ArtifactType.HOOK,
                    event = AgentEvent.PRE_TOOL_USE,
                    shadow = shadow,
                    note = triggerNote(node, workflow, "no trigger found: set \"Runs before\""),
                ),
                Produced(
                    ArtifactType.SCRIPT,
                    where = run(node).where ?: RunLocation.WORKSTATION,
                    shadow = shadow,
                    note = "proposed workflow: logged, never run".takeIf { shadow },
                ),
            )
        }
    }

    private fun gateProduces(node: WorkflowNode, shadow: Boolean): List<Produced> {
        val suggested = suggestions(node).actions?.value?.takeIf { openSuggestion(node, SuggestionField.ACTIONS) }
        val note = suggested?.let {
            "actions suggested by the analysis (${it.joinToString(", ").ifEmpty { "none" }}): the bridge ignores " +
                "them until someone confirms"
        }
        return listOf(
            Produced(ArtifactType.HOOK, event = AgentEvent.PRE_TOOL_USE, shadow = shadow, note = note),
            Produced(
                ArtifactType.APPROVAL,
                shadow = shadow,
                note = "proposed workflow: logged, never blocking".takeIf { shadow },
            ),
            Produced(ArtifactType.RECEIPT),
        )
    }

    private fun notifyProduces(node: WorkflowNode, workflow: Workflow, shadow: Boolean): List<Produced> {
        val channel = run(node).channel.orEmpty()
        val suggestedChannel = suggestions(node).channel?.value
            ?.takeIf { openSuggestion(node, SuggestionField.CHANNEL) }
        val note = when {
            shadow -> "proposed workflow: logged, never sent"
            channel.isNotEmpty() -> null
            suggestedChannel != null ->
                "channel suggested ($suggestedChannel): logged, never sent until someone chooses it"
            else -> "no channel chosen: logged, never sent"
        }
        return listOf(
            Produced(
                ArtifactType.HOOK,
                event = AgentEvent.POST_TOOL_USE,
                shadow = shadow || channel.isEmpty(),
                note = triggerNote(node, workflow, "no trigger found: set \"Runs after\""),
            ),
            Produced(
                ArtifactType.NOTIFY,
                where = RunLocation.OSTROM,
                shadow = shadow || channel.isEmpty(),
                suggested = channel.isEmpty() && suggestedChannel != null,
                note = note,
            ),
        )
    }

    private const val SUGGESTED_COMMAND_NOTE = "until someone takes the suggested command, the agent is asked to check"
    private const val NO_COMMAND_NOTE = "no command: the agent is asked to check, nothing runs"
    private const val STEP_NOTE = "instructions and linked rules, loaded when the agent needs them"
    private const val AGENT_NOTE = "Claude Code, OpenCode and Codex (ostrom_* role); a skill for the other agents"
}
