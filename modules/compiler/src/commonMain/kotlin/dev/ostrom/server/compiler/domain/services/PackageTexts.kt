// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.services

import dev.ostrom.core.api.models.CompiledNode
import dev.ostrom.core.api.models.CompiledNotify
import dev.ostrom.core.api.models.CompiledScript
import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowNodeKind
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.RuleDoc

/** The texts a session package carries for a workflow (`compiler/compile.ts`), byte for byte. */
object PackageTexts {
    private val CHANNEL_PREFIX = Regex("^(log|slack|discord):")

    /**
     * A workflow's slug, `workflow` when empty.
     *
     * @param text The name or label.
     * @return The slug.
     */
    fun slug(text: String): String = JsText.slug(text, "workflow")

    /**
     * The name of a sub-agent for Codex, which takes only `[a-z0-9_]` (`ostrom_<slug>`).
     *
     * @param label The node's label.
     * @return The name.
     */
    fun codexAgentName(label: String): String = "ostrom_${slug(label).replace('-', '_')}"

    /**
     * The check's script: the command, run with `set -e` by the launcher in a sandbox.
     *
     * @param workflow The workflow.
     * @param node The check.
     * @param command The command.
     * @return The script.
     */
    fun script(workflow: Workflow, node: WorkflowNode, command: String): String = listOf(
        "#!/bin/sh",
        "# Ostrom check \"${node.label}\" (${workflow.name}, node ${node.id}). Compiled by Ostrom; run by the " +
            "launcher in a sandbox,",
        "# in the repository, with no network and no secret. Exit 0 = passed.",
        "set -e",
        command.trim(),
        "",
    ).joinToString("\n")

    /**
     * The sub-agent of an agent node for Claude Code.
     *
     * @param workflow The workflow.
     * @param node The agent node.
     * @return Its Markdown file.
     */
    fun subagent(workflow: Workflow, node: WorkflowNode): String = listOf(
        "---",
        "name: ostrom-${slug(node.label)}",
        "description: ${JsText.quote("${node.label} — step of \"${workflow.name}\", compiled by Ostrom.")}",
        "---",
        "",
        instructions(node),
        "",
        doneLine(node),
        "",
    ).joinToString("\n")

    /**
     * The same sub-agent as a Codex role.
     *
     * @param workflow The workflow.
     * @param node The agent node.
     * @return Its TOML file.
     */
    fun codexAgent(workflow: Workflow, node: WorkflowNode): String = listOf(
        "name = ${JsText.quote(codexAgentName(node.label))}",
        "description = ${JsText.quote("${node.label}: step of \"${workflow.name}\", compiled by Ostrom.")}",
        "developer_instructions = ${JsText.quote(listOf(instructions(node), "", doneLine(node)).joinToString("\n"))}",
        "",
    ).joinToString("\n")

    private fun instructions(node: WorkflowNode): String =
        listOf(NodeRules.run(node).instructions, node.detail)
            .map { it.orEmpty().trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: node.label

    private fun doneLine(node: WorkflowNode): String =
        "When you are done, call node_done (MCP server \"ostrom-workflow\") with node \"${node.id}\" and a " +
            "one-line summary."

    /**
     * The workflow's skill: its steps in order, what Ostrom runs before an action, who says yes, who is told.
     *
     * @param workflow The workflow.
     * @param ordered Its nodes in order.
     * @param compiled Its compiled nodes.
     * @param scripts The package's scripts.
     * @param notes The package's notifications.
     * @param rules The organization's rules.
     * @param repo The repository.
     * @return The skill's Markdown.
     */
    @Suppress("LongParameterList")
    fun skill(
        workflow: Workflow,
        ordered: List<WorkflowNode>,
        compiled: List<CompiledNode>,
        scripts: List<CompiledScript>,
        notes: List<CompiledNotify>,
        rules: List<RuleDoc>,
        repo: String?,
    ): String {
        val inRepo = repo?.let { " in $it" }.orEmpty()
        val description = "How the team ships \"${workflow.name}\"$inRepo: the steps in order, what Ostrom runs " +
            "before an action, who says yes, who is told. From Ostrom."
        val lines = mutableListOf(
            "---",
            "name: ostrom-wf-${slug(workflow.name)}",
            "description: ${JsText.quote(description)}",
            "---",
            "",
            "# ${workflow.name}",
            "",
            if (workflow.status == WorkflowStatus.PROPOSED) PROPOSED else DECIDED,
            "",
        )
        for (node in ordered) {
            lines += "## ${node.label} (${node.kind.name.lowercase()})"
            val detail = node.detail.orEmpty()
            if (detail.isNotEmpty()) lines += listOf("", detail.trim())
            val classes = compiled.first { it.id == node.id }.trigger?.classes.orEmpty()
            lines += nodeLines(node, classes, scripts, notes)
            val linked = node.ruleIds.orEmpty().mapNotNull { id -> rules.firstOrNull { it.id == id } }
            if (linked.isNotEmpty()) {
                lines += listOf("", "Rules for this step:") + linked.map { "- ${it.statement.trim()} (${it.id})" }
            }
            lines += ""
        }
        return lines.joinToString("\n")
    }

    private fun nodeLines(
        node: WorkflowNode,
        classes: List<String>,
        scripts: List<CompiledScript>,
        notes: List<CompiledNotify>,
    ): List<String> = when (node.kind) {
        WorkflowNodeKind.CHECK -> {
            val script = scripts.firstOrNull { it.nodeId == node.id }?.takeIf { it.suggested == null }
            val before = classes.joinToString(", ").ifEmpty { "the action" }
            listOf(
                "",
                script?.let {
                    "Before $before, Ostrom runs `${it.command}` in a sandbox (no network, no secret). If it fails, " +
                        "the action is refused with the output: fix the cause, never skip or weaken the check, then " +
                        "run the same command again."
                } ?: "Check it yourself before the action: nothing runs automatically for this step.",
            )
        }
        WorkflowNodeKind.GATE -> {
            val who = node.approvers.orEmpty().joinToString(" or ").ifEmpty { "the production approver" }
            listOf(
                "",
                "A human yes is needed from $who. When Ostrom refuses the action \"waiting for a yes\", run the " +
                    "`ostrom wait <id>` command it gives, then the same command again. Never work around a gate.",
            )
        }
        // Every notification node compiles to a note.
        WorkflowNodeKind.NOTIFY -> notes.first { it.nodeId == node.id }.let {
            listOf(
                "",
                "After the action, Ostrom tells ${it.channel.replaceFirst(CHANNEL_PREFIX, "")} by itself: do not " +
                    "send the message yourself.",
            )
        }
        WorkflowNodeKind.AGENT -> {
            val name = codexAgentName(node.label)
            val instructions = NodeRules.run(node).instructions.orEmpty()
            listOf(
                "",
                "Hand this step to a sub-agent where your agent has them: `ostrom-${slug(node.label)}` in Claude " +
                    "Code; in Codex, spawn_agent with agent_type `$name` (and task_name `$name`). Otherwise do it " +
                    "yourself. Then call `node_done` (MCP server \"ostrom-workflow\") with node \"${node.id}\" and a " +
                    "one-line summary of what was really done; never report a sub-agent that did not run.",
            ) + if (instructions.isEmpty()) emptyList() else listOf("", instructions)
        }
        else -> emptyList()
    }

    private const val DECIDED = "Decided by the team: Ostrom enforces it in this session."
    private const val PROPOSED = "**Proposed, not decided yet**: Ostrom only logs what this workflow would do; " +
        "nothing is blocked or run because of it."
}
