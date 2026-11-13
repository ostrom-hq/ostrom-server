// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind

/**
 * The checks of the sub-workflows of a workflow the server plays (TeamCode `checkSubWorkflows`): each one exists, none
 * leads back to the workflow saved (the message gives the path), the chain is no deeper than `limits.maxDepth`, and the
 * arguments of a call cover the called workflow's required inputs and name no unknown one. They read the organization's
 * other workflows, so a save makes them under the organization's workflow lock.
 */
object CallChecks {
    /**
     * Checks the calls of a workflow.
     *
     * @param workflow The workflow saved.
     * @param others The organization's other workflows, by id.
     * @throws IllegalArgumentException With what to fix, naming the node or the path.
     */
    fun check(workflow: Workflow, others: Map<String, Workflow>) {
        val calls = calls(workflow)
        for (call in calls) {
            require(call.id != workflow.id) { "Node ${call.node.id}: a workflow cannot call itself." }
            require(call.id in others) {
                "Node ${call.node.id}: the workflow ${call.id.take(WorkflowChecks.MAX_TITLE)} does not exist."
            }
        }
        val maxDepth = workflow.limits?.maxDepth ?: WorkflowChecks.DEFAULT_DEPTH
        val deepest = calls.fold(0) { most, call -> maxOf(most, depth(call.id, listOf(workflow.id), others)) }
        require(deepest <= maxDepth) { "Sub-workflows are nested $deepest deep; this workflow allows $maxDepth." }
        calls.forEach { arguments(it, others.getValue(it.id)) }
    }

    // A workflow node and the workflow it names.
    private class Call(val node: WorkflowNode, val id: String)

    private fun calls(workflow: Workflow): List<Call> = workflow.nodes.mapNotNull { node ->
        node.workflowId?.takeIf { node.kind == WorkflowNodeKind.WORKFLOW }?.let { Call(node, it) }
    }

    // [path] starts with the workflow saved, so a way back to it is the first thing a chain meets. A workflow another
    // one calls and that is gone ends the chain: its own save or delete was checked then.
    private fun depth(id: String, path: List<String>, others: Map<String, Workflow>): Int {
        val trail = (path + id).joinToString(" -> ") { it.take(WorkflowChecks.MAX_TITLE) }
        require(id != path.first()) { "Workflow calls loop back to this one ($trail)." }
        require(id !in path) { "Workflow calls loop ($trail)." }
        val next = others[id]?.let(::calls).orEmpty().map { it.id }.toSet()
        return 1 + next.fold(0) { most, called -> maxOf(most, depth(called, path + id, others)) }
    }

    private fun arguments(call: Call, called: Workflow) {
        val given = call.node.arguments.orEmpty().keys
        val declared = called.inputs.map { it.id }
        val missing = called.inputs.filter { it.required && it.default == null && it.id !in given }
        require(missing.isEmpty()) {
            "Node ${call.node.id}: workflow ${called.id} needs the input ${missing.first().id}."
        }
        require(given.all { it in declared }) { "Node ${call.node.id}: workflow ${called.id} has no such input." }
    }
}
