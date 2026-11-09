// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.NodeCondition
import run.ostrom.core.api.models.NodeConditionOp
import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a condition says of a run: whether it holds, or why it does not evaluate.
 *
 * @property holds True or false, the outcome of the check node's visit; null when it does not evaluate.
 * @property reason Why it does not evaluate.
 */
data class ConditionVerdict(val holds: Boolean? = null, val reason: String? = null)

/**
 * The typed condition of a `check` node (`WorkflowNode.condition`): one value of a step the run visited before,
 * compared, never an expression nor code. Checked when a workflow is saved, evaluated when a run visits the node; a
 * condition that does not evaluate never chooses a branch.
 */
object NodeConditions {
    /** The most segments (`.<key>`, `[<index>]`) of a path after `output`. */
    const val MAX_SEGMENTS: Int = 10

    private val PATH = Regex("outcome|output(\\.[A-Za-z0-9_-]{1,64}|\\[[0-9]{1,4}\\]){0,$MAX_SEGMENTS}")
    private val SEGMENT = Regex("\\.([A-Za-z0-9_-]{1,64})|\\[([0-9]{1,4})\\]")

    /**
     * Checks the conditions of a workflow before it is saved.
     *
     * @param workflow The workflow.
     * @throws IllegalArgumentException When a condition is out of its form, with the node and the reason.
     */
    fun check(workflow: Workflow) {
        val ids = workflow.nodes.map { it.id }.toSet()
        for (node in workflow.nodes) {
            val edges = workflow.edges.filter { it.from == node.id }
            node.condition?.let { check(node, it, ids, edges) }
        }
    }

    /**
     * Evaluates a check node's condition on the visits before the node's own.
     *
     * @param condition The condition, null when the node has none.
     * @param visits The run's visits before the check node's.
     *
     * @return Whether it holds, or why it does not evaluate.
     */
    fun evaluate(condition: NodeCondition?, visits: List<NodeVisit>): ConditionVerdict =
        if (condition == null) ConditionVerdict(reason = NO_CONDITION) else evaluated(condition, visits)

    private fun check(node: WorkflowNode, condition: NodeCondition, ids: Set<String>, edges: List<WorkflowEdge>) {
        val at = "node ${node.id}: condition"
        val value = condition.value
        require(node.kind == WorkflowNodeKind.CHECK) { "$at: only a check node has a condition." }
        require(condition.step in ids) { "$at.step: ${condition.step.take(MAX_SHOWN)} is not a node of the workflow." }
        require(condition.path.length <= MAX_TEXT_FIELD && PATH.matches(condition.path)) { "$at.path: $PATH_FORM" }
        require(condition.op != NodeConditionOp.UNKNOWN) { "$at.op: equals, notEquals, exists or matches." }
        require((value == null) == (condition.op == NodeConditionOp.EXISTS)) { "$at.value: $VALUE_FORM" }
        val problem = value.takeIf { condition.op == NodeConditionOp.MATCHES }?.let(::problem)
        require(problem == null) { "$at.value: $problem" }
        require(edges.all { it.on in BRANCHES }) { "$at: every edge out of the node says on true or on false." }
    }

    // A step the run did not visit has no value, as a missing key: `exists` is false, the other operators fail.
    private fun evaluated(condition: NodeCondition, visits: List<NodeVisit>): ConditionVerdict {
        val text = visits.lastOrNull { it.nodeId == condition.step }?.let { valueOf(it, condition.path) }
        val read = "${condition.path} of step ${condition.step}"
        return when {
            condition.op == NodeConditionOp.EXISTS -> ConditionVerdict(text != null)
            text == null -> ConditionVerdict(reason = "The condition reads $read, which has no value.")
            text.length > LinearPattern.MAX_TEXT -> ConditionVerdict(reason = "The condition reads $read, $TOO_LONG")
            else -> compared(condition, text)
        }
    }

    private fun compared(condition: NodeCondition, text: String): ConditionVerdict {
        val value = condition.value
        return when {
            value == null || condition.op == NodeConditionOp.UNKNOWN ->
                ConditionVerdict(reason = "The condition's ${condition.op.value} has no value to compare with.")
            condition.op == NodeConditionOp.EQUALS -> ConditionVerdict(text == value)
            condition.op == NodeConditionOp.NOT_EQUALS -> ConditionVerdict(text != value)
            else -> matched(value, text)
        }
    }

    private fun matched(pattern: String, text: String): ConditionVerdict = try {
        ConditionVerdict(LinearPattern.compile(pattern).find(text))
    } catch (expected: IllegalArgumentException) {
        ConditionVerdict(reason = "The condition's pattern: ${expected.message}")
    }

    // The value a path reads of a visit: its outcome, its output, or a value of its output read as JSON.
    private fun valueOf(visit: NodeVisit, path: String): String? = when (path) {
        OUTCOME -> visit.outcome
        OUTPUT -> visit.output
        else -> visit.output?.let { read(it, path.removePrefix(OUTPUT)) }
    }

    private fun read(output: String, segments: String): String? {
        val root: JsonElement? = try {
            Json.parseToJsonElement(output)
        } catch (expected: SerializationException) {
            null
        }
        return when (val leaf = SEGMENT.findAll(segments).fold(root, ::member)) {
            null, JsonNull -> null
            is JsonPrimitive -> leaf.content
            else -> leaf.toString()
        }
    }

    // A member of an object (`.<key>`) or an element of an array (`[<index>]`); null for a value of the other kind.
    private fun member(element: JsonElement?, segment: MatchResult): JsonElement? {
        val (key, index) = segment.destructured
        val inObject = (element as? JsonObject)?.get(key)
        return if (key.isNotEmpty()) inObject else (element as? JsonArray)?.getOrNull(index.toInt())
    }

    private fun problem(pattern: String): String? = try {
        LinearPattern.compile(pattern)
        null
    } catch (expected: IllegalArgumentException) {
        expected.message
    }
}

private const val OUTCOME = "outcome"
private val BRANCHES = setOf("true", "false")
private val TOO_LONG = "longer than ${LinearPattern.MAX_TEXT} characters."
private const val OUTPUT = "output"
private const val MAX_SHOWN = 200
private const val MAX_TEXT_FIELD = 200
private const val NO_CONDITION = "The check node has no condition: the server plays a check node by its condition only."
private const val PATH_FORM = "outcome, output, or output followed by 1 to 10 segments .<key> (1 to 64 letters, " +
    "digits, _ or -) or [<index>] (at most 4 digits), as output.a.b[0].c; 200 characters in all."
private const val VALUE_FORM = "required for equals, notEquals and matches, absent for exists."
