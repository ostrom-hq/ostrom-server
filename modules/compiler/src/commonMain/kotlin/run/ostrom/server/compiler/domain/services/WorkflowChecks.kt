// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowLimits
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind

/**
 * What a workflow must be to be played by the server (TeamCode `WorkflowRules.validate`, docs/workflows.md "Checks at
 * save"), checked when it is saved: its header, inputs and limits, its graph (a start, edges that go somewhere, every
 * node reached, a way to end), and each node ([NodeChecks]). These checks apply to the workflows the server runs,
 * those that hold an agent, a wait or a workflow node; a workflow of the harness only (trigger, step, check, gate,
 * notify) is compiled for a session, not walked by the engine, and keeps its looser rules. Every refusal names what is
 * wrong, and the node.
 */
object WorkflowChecks {
    /** The longest name. */
    const val MAX_NAME: Int = 120

    /** The most inputs. */
    const val MAX_INPUTS: Int = 16

    /** The most nodes a limit allows, and the default. */
    const val MAX_NODES: Int = 100

    /** The default of `limits.maxNodes`. */
    const val DEFAULT_NODES: Int = 50

    /** The most visits a run makes in all, and the default of `limits.maxVisits`. */
    const val MAX_TOTAL_VISITS: Int = 500

    /** The default of `limits.maxVisits`. */
    const val DEFAULT_VISITS: Int = 100

    /** The most sub-workflows deep, and the default of `limits.maxDepth`. */
    const val MAX_DEPTH: Int = 5

    /** The default of `limits.maxDepth`. */
    const val DEFAULT_DEPTH: Int = 3

    /** The most visits of one node. */
    const val MAX_NODE_VISITS: Int = 20

    internal const val MAX_DESCRIPTION = 2_000
    internal const val MAX_TITLE = 200
    internal const val MAX_INPUT_LENGTH = 32_000

    private val ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val SERVER_KINDS = setOf(WorkflowNodeKind.AGENT, WorkflowNodeKind.WAIT, WorkflowNodeKind.WORKFLOW)

    /**
     * Tells whether the server plays a workflow: it holds an agent, a wait or a workflow node.
     *
     * @param workflow The workflow.
     * @return True when the save checks of this object apply.
     */
    fun runsOnServer(workflow: Workflow): Boolean = workflow.nodes.any { it.kind in SERVER_KINDS }

    /**
     * Checks a workflow, when the server plays it.
     *
     * @param workflow The workflow.
     * @throws IllegalArgumentException With what to fix, naming the node when one is at fault.
     */
    fun check(workflow: Workflow) {
        if (!runsOnServer(workflow)) return
        header(workflow)
        limits(workflow.limits ?: WorkflowLimits())
        inputs(workflow)
        graph(workflow)
        val inputIds = workflow.inputs.map { it.id }.toSet()
        val ids = workflow.nodes.map { it.id }.toSet()
        workflow.nodes.forEach { NodeChecks.check(it, ids, inputIds, workflow.edges.filter { e -> e.from == it.id }) }
    }

    /**
     * The workflows, among [workflows], that call [id] with a workflow node.
     *
     * @param id The called workflow.
     * @param workflows The organization's other workflows.
     * @return The callers.
     */
    fun callersOf(id: String, workflows: Collection<Workflow>): List<Workflow> = workflows.filter { other ->
        other.id != id && other.nodes.any { it.kind == WorkflowNodeKind.WORKFLOW && it.workflowId == id }
    }

    private fun header(workflow: Workflow) {
        require(workflow.name.trim().length in 1..MAX_NAME) { "The name is 1 to $MAX_NAME characters." }
        require(workflow.name.none { it.isISOControl() }) { "The name has no control characters." }
        require(workflow.description.orEmpty().length <= MAX_DESCRIPTION) {
            "The description is at most $MAX_DESCRIPTION characters."
        }
    }

    private fun limits(limits: WorkflowLimits) {
        require((limits.maxNodes ?: DEFAULT_NODES) in 1..MAX_NODES) { "limits.maxNodes is 1 to $MAX_NODES." }
        require((limits.maxVisits ?: DEFAULT_VISITS) in 1..MAX_TOTAL_VISITS) {
            "limits.maxVisits is 1 to $MAX_TOTAL_VISITS."
        }
        require((limits.maxDepth ?: DEFAULT_DEPTH) in 0..MAX_DEPTH) { "limits.maxDepth is 0 to $MAX_DEPTH." }
    }

    private fun inputs(workflow: Workflow) {
        require(workflow.inputs.size <= MAX_INPUTS) { "At most $MAX_INPUTS inputs." }
        require(workflow.inputs.map { it.id }.toSet().size == workflow.inputs.size) { "Input ids are unique." }
        workflow.inputs.forEach { input ->
            require(ID.matches(input.id)) {
                "Input ${input.id.take(MAX_TITLE)}: an id starts with a letter, then letters, digits or _ (64 at most)."
            }
            require(input.label.trim().length in 1..MAX_TITLE) { "Input ${input.id}: a label of 1 to $MAX_TITLE." }
            require(input.maxLength in 1..MAX_INPUT_LENGTH) {
                "Input ${input.id}: maxLength is 1 to $MAX_INPUT_LENGTH."
            }
            require(input.default.orEmpty().length <= input.maxLength) {
                "Input ${input.id}: the default is longer than maxLength."
            }
        }
    }

    private fun graph(workflow: Workflow) {
        val nodes = workflow.nodes
        val ids = nodes.map { it.id }
        val maxNodes = workflow.limits?.maxNodes ?: DEFAULT_NODES
        require(nodes.size <= maxNodes) { "A workflow has 1 to $maxNodes nodes." }
        require(ids.toSet().size == ids.size) { "Node ids are unique." }
        val start = workflow.start ?: ids.first()
        require(start in ids) { "The start node ${start.take(MAX_TITLE)} does not exist." }
        require(nodes.first { it.id == start }.kind != WorkflowNodeKind.WAIT) {
            "Node $start: the start node cannot ask a person; an agent or a trigger works first."
        }
        workflow.edges.forEach { edge ->
            val to = edge.to
            require(edge.from in ids) { "An edge starts from ${edge.from.take(MAX_TITLE)}, which is not a node." }
            require(to == null || to in ids) {
                "Node ${edge.from}: an edge goes to ${to.toString().take(MAX_TITLE)}, which is not a node."
            }
        }
        val reached = reachable(start, workflow.edges)
        for (node in nodes) require(node.id in reached) { "Node ${node.id}: not reachable from the start." }
        require(ends(nodes, workflow.edges)) {
            "No node ends the workflow: give a node no edge, or an edge no target."
        }
    }

    // A node without any edge ends the run, as an edge without a target does.
    private fun ends(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>): Boolean =
        edges.any { it.to == null } || nodes.any { node -> edges.none { it.from == node.id } }

    private fun reachable(start: String, edges: List<WorkflowEdge>): Set<String> {
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque(listOf(start))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (seen.add(id)) stack.addAll(edges.filter { it.from == id }.mapNotNull { it.to })
        }
        return seen
    }
}
