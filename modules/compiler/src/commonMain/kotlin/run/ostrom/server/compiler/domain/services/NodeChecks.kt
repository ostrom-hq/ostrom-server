// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.Deliverable
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind

/**
 * The checks of one node of a workflow the server plays ([WorkflowChecks]): its id, title and visits, the templates of
 * its texts, what its kind needs (an agent's instruction and deliverable, a wait's question, a sub-workflow's id), and
 * that every outcome it can end with has an edge, and every edge an outcome.
 */
internal object NodeChecks {
    private const val NEXT = "next"
    private const val DELIVERED = "delivered"
    private const val DONE = "done"
    private const val MAX_ACTIONS = 8

    private val ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val NODE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    private val PLACEHOLDER = Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}")
    private val INPUT_REF = Regex("inputs\\.([^.]+)")
    private val NODE_REF = Regex("nodes\\.([^.]+)\\.output")
    private val DOMAIN = Regex("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
    private val DELIVERABLES = setOf("artifact", "url", "text", "json")

    /**
     * Checks a node.
     *
     * @param node The node.
     * @param ids The ids of the workflow's nodes.
     * @param inputs The ids of the workflow's inputs.
     * @param out The edges that leave the node.
     * @throws IllegalArgumentException With what to fix, naming the node.
     */
    fun check(node: WorkflowNode, ids: Set<String>, inputs: Set<String>, out: List<WorkflowEdge>) {
        val at = "Node ${node.id.take(WorkflowChecks.MAX_TITLE)}"
        require(NODE_ID.matches(node.id)) { "$at: an id is letters, digits, _ or - (64 at most)." }
        require(node.label.trim().length in 1..WorkflowChecks.MAX_TITLE) {
            "$at: a label of 1 to ${WorkflowChecks.MAX_TITLE} characters."
        }
        require((node.maxVisits ?: 1) in 1..WorkflowChecks.MAX_NODE_VISITS) {
            "$at: maxVisits is 1 to ${WorkflowChecks.MAX_NODE_VISITS}."
        }
        templates(node).forEach { text -> placeholders(text).forEach { known(at, it, node.id, ids, inputs) } }
        val outcomes = when (node.kind) {
            WorkflowNodeKind.AGENT -> agent(at, node)
            WorkflowNodeKind.WAIT -> wait(at, node)
            WorkflowNodeKind.WORKFLOW -> child(at, node)
            WorkflowNodeKind.CHECK -> node.condition?.let { setOf("true", "false") }
            else -> null
        }
        if (outcomes != null) edges(at, node.kind == WorkflowNodeKind.CHECK, outcomes, out)
    }

    /**
     * What a text names between double braces.
     *
     * @param text A text of a node.
     * @return `inputs.x` and `nodes.id.output`, anything else between braces too (refused at save).
     */
    fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toList()

    // The texts a template renders in: an agent's instruction, a wait's body, a deliverable's label, an argument.
    private fun templates(node: WorkflowNode): List<String> =
        listOfNotNull(node.run?.instructions, node.wait?.body, node.run?.deliverable?.label) +
            node.arguments?.values.orEmpty()

    private fun known(at: String, name: String, self: String, ids: Set<String>, inputs: Set<String>) {
        val input = INPUT_REF.matchEntire(name)?.let { it.groupValues[1] }
        val node = NODE_REF.matchEntire(name)?.let { it.groupValues[1] }
        val ok = when {
            input != null -> input in inputs
            node != null -> node in ids && node != self
            else -> false
        }
        require(ok) {
            "$at: {{${name.take(WorkflowChecks.MAX_TITLE)}}} is not known; use {{inputs.<id>}} or " +
                "{{nodes.<id>.output}} of another node."
        }
    }

    private fun agent(at: String, node: WorkflowNode): Set<String> {
        val run = node.run
        require(run != null && !run.instructions.isNullOrBlank()) {
            "$at: an agent node run by the server needs run.instructions."
        }
        run.deliverable?.let { deliverable(at, it) }
        return setOf(DELIVERED)
    }

    private fun deliverable(at: String, deliverable: Deliverable) {
        val domain = deliverable.domain
        require(deliverable.type in DELIVERABLES) { "$at: a deliverable is an artifact, a url, a text or a json." }
        require(domain == null || (deliverable.type == "url" && DOMAIN.matches(domain))) {
            "$at: only a url deliverable has a domain, such as github.com."
        }
        require(deliverable.schema == null || deliverable.type == "json") {
            "$at: only a json deliverable has a schema."
        }
        require(deliverable.label.orEmpty().length <= WorkflowChecks.MAX_TITLE) {
            "$at: the deliverable's label is at most ${WorkflowChecks.MAX_TITLE} characters."
        }
    }

    private fun wait(at: String, node: WorkflowNode): Set<String> {
        val question = node.wait
        require(question != null) { "$at: a wait node asks a question; set wait." }
        require(question.title.trim().length in 1..WorkflowChecks.MAX_TITLE) {
            "$at: the question's title is 1 to ${WorkflowChecks.MAX_TITLE} characters."
        }
        val actions = question.actions.map { it.id }
        require(actions.size <= MAX_ACTIONS) { "$at: at most $MAX_ACTIONS actions." }
        require(actions.toSet().size == actions.size) { "$at: action ids are unique." }
        require(actions.all { ID.matches(it) }) { "$at: an action id is a letter then letters, digits or _." }
        return actions.toSet().ifEmpty { setOf(DONE) }
    }

    private fun child(at: String, node: WorkflowNode): Set<String> {
        require(!node.workflowId.isNullOrBlank()) { "$at: a workflow node names the workflow to run." }
        require(node.arguments.orEmpty().keys.all { ID.matches(it) }) { "$at: argument names are input ids." }
        return setOf(DELIVERED)
    }

    // Every edge takes an outcome of the node, no two the same, and every outcome goes somewhere: an edge of its
    // own, or the default one (no `on`, or `next`). A node with no edge ends the run, but a check, whose false ends
    // nothing.
    private fun edges(at: String, branches: Boolean, outcomes: Set<String>, out: List<WorkflowEdge>) {
        val taken = out.map { it.on ?: NEXT }
        val allowed = if (branches) outcomes else outcomes + NEXT
        require(taken.all { it in allowed }) {
            "$at: no such outcome on an edge; use ${allowed.sorted().joinToString(", ")}."
        }
        require(taken.toSet().size == taken.size) { "$at: two edges on the same outcome." }
        if (out.isEmpty() && !branches) return
        for (outcome in outcomes.sorted()) {
            require(outcome in taken || (!branches && NEXT in taken)) {
                "$at: say where $outcome goes (a node, or an edge without target to end the workflow)."
            }
        }
    }
}
