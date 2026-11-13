// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.AgentEvent
import run.ostrom.core.api.models.ArtifactType
import run.ostrom.core.api.models.Produced
import run.ostrom.core.api.models.RunLocation
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus

/** What each node kind produces in a session (`shared/src/compiler.ts`), in the prototype's order and wording. */
object NodeProduces {
    /**
     * What a node produces in a session, given its kind and settings.
     *
     * @param node The node.
     * @param workflow Its workflow.
     * @return The artifacts, in the prototype's order and wording.
     */
    fun produces(node: WorkflowNode, workflow: Workflow): List<Produced> {
        val shadow = workflow.status == WorkflowStatus.PROPOSED
        val unlinked = NodeRules.unlinkedRules(node)
            .takeIf { NodeRules.openSuggestion(node, SuggestionField.RULE_IDS) }
        val rulesNote = unlinked?.let {
            "$it rule${if (it > 1) "s" else ""} suggested by the analysis: not in the skill until someone links them"
        }
        val produced = when (node.kind) {
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
        // A proposed workflow's text comes from a repository nobody trusted yet: no skill, no sub-agent, until a
        // person accepts it (its checks, gates and notifications stay shadow records).
        return if (shadow) produced.filter { it.type !in UNTIL_ACCEPTED } else produced
    }

    // The note of a hook without a trigger: the analysis's suggestion when there is one, else [fallback].
    private fun triggerNote(node: WorkflowNode, workflow: Workflow, fallback: String): String? {
        val none = NodeRules.triggerOf(node, workflow).from == "none"
        val suggested = NodeRules.suggestions(node).on
            ?.takeIf { NodeRules.openSuggestion(node, SuggestionField.ON) }?.value
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
        val command = NodeRules.run(node).command.orEmpty()
        return when {
            command.isEmpty() && NodeRules.openSuggestion(node, SuggestionField.COMMAND) -> {
                val found = NodeRules.triggerOf(node, workflow, suggested = true).from != "none"
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
                    where = NodeRules.run(node).where ?: RunLocation.WORKSTATION,
                    shadow = shadow,
                    note = "proposed workflow: logged, never run".takeIf { shadow },
                ),
            )
        }
    }

    private fun gateProduces(node: WorkflowNode, shadow: Boolean): List<Produced> {
        val suggested = NodeRules.suggestions(node).actions
            ?.takeIf { NodeRules.openSuggestion(node, SuggestionField.ACTIONS) }?.value
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
        val channel = NodeRules.run(node).channel.orEmpty()
        val suggestedChannel = NodeRules.suggestions(node).channel
            ?.takeIf { NodeRules.openSuggestion(node, SuggestionField.CHANNEL) }?.value
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

// What a proposed workflow produces nothing of: the skill and the sub-agent written from what a repository says.
private val UNTIL_ACCEPTED = setOf(ArtifactType.SKILL, ArtifactType.SUBAGENT, ArtifactType.MCP)
