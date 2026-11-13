// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.engine

import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.WorkflowRun

/**
 * The one renderer of a run's texts (TeamCode `WorkflowTemplate`): an agent node's instructions, a wait node's body and
 * a workflow node's arguments. `{{inputs.<id>}}` is the run's input and `{{nodes.<id>.output}}` the output of that
 * node's latest finished visit, as plain text; nothing else between braces is read, and an unknown name renders
 * empty. Control characters other than line breaks and tabs are removed, and a text longer than a session's first
 * message is cut, ending with `[truncated]`.
 */
object RunTemplates {
    /** The longest text rendered: a session's first message (the runners module's bound). */
    const val MAX_TEXT: Int = 20_000

    /** What ends a text that was cut. */
    const val TRUNCATED: String = "\n[truncated]"

    /**
     * Renders a text of a run.
     *
     * @param text The template.
     * @param run The run: its inputs, and its visits' outputs.
     *
     * @return The text.
     */
    fun render(text: String, run: WorkflowRun): String {
        val outputs = outputs(run.visits)
        val rendered = PLACEHOLDER.replace(text) { match ->
            val name = match.groupValues[1]
            val input = INPUT.matchEntire(name)?.let { run.inputs[it.groupValues[1]] }
            (input ?: NODE_OUTPUT.matchEntire(name)?.let { outputs[it.groupValues[1]] }).orEmpty()
        }
        return cut(rendered.filterNot { it.isISOControl() && it != '\n' && it != '\t' })
    }

    /**
     * A text within [MAX_TEXT], ending with [TRUNCATED] when it was cut.
     *
     * @param text The text.
     *
     * @return The text, cut if needed.
     */
    fun cut(text: String): String =
        if (text.length <= MAX_TEXT) text else text.take(MAX_TEXT - TRUNCATED.length) + TRUNCATED

    /**
     * The output of each node's latest finished visit.
     *
     * @param visits The run's visits, in order.
     *
     * @return The outputs by node.
     */
    fun outputs(visits: List<NodeVisit>): Map<String, String> = visits
        .filter { it.status == RunMoves.DONE }
        .mapNotNull { visit -> visit.output?.let { visit.nodeId to it } }
        .toMap()
}

// Top-level, not in the object: a private property of an object has a getter Kover counts.
private val PLACEHOLDER = Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}")
private val INPUT = Regex("inputs\\.([^.]+)")
private val NODE_OUTPUT = Regex("nodes\\.([^.]+)\\.output")
