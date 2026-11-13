// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.core.api.models.Deliverable
import run.ostrom.core.api.models.NodeCondition
import run.ostrom.core.api.models.NodeConditionOp
import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.WaitAction
import run.ostrom.core.api.models.WaitSpec
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowInput
import run.ostrom.core.api.models.WorkflowLimits
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind.AGENT
import run.ostrom.core.api.models.WorkflowNodeKind.CHECK
import run.ostrom.core.api.models.WorkflowNodeKind.STEP
import run.ostrom.core.api.models.WorkflowNodeKind.TRIGGER
import run.ostrom.core.api.models.WorkflowNodeKind.WAIT
import run.ostrom.core.api.models.WorkflowNodeKind.WORKFLOW
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.services.CallChecks
import run.ostrom.server.compiler.domain.services.NodeChecks
import run.ostrom.server.compiler.domain.services.WorkflowChecks
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The save checks of a workflow the server plays (TC-WFL-5), one refusal at a time, each naming what is wrong. */
class WorkflowChecksTest {
    private fun agent(id: String = "a", instructions: String? = "Fix {{inputs.branch}}") =
        node(id, AGENT, run = NodeRun(instructions = instructions))

    private fun ask(id: String = "q", vararg actions: String) = node(id, WAIT).copy(
        wait = WaitSpec("Ship it?", actions = actions.map { WaitAction(it, it) }),
    )

    private fun call(id: String, workflowId: String?, vararg arguments: Pair<String, String>) =
        node(id, WORKFLOW).copy(workflowId = workflowId, arguments = arguments.toMap().ifEmpty { null })

    private fun edge(from: String, to: String?, on: String? = null) = WorkflowEdge(from, to, on = on)

    @Suppress("LongParameterList") // A fixture: one named argument with a default per field.
    private fun flow(
        nodes: List<WorkflowNode> = listOf(agent()),
        edges: List<WorkflowEdge> = emptyList(),
        id: String = "w",
        start: String? = null,
        name: String = "Flow",
        inputs: List<WorkflowInput> = listOf(WorkflowInput("branch", "Branch", required = true)),
        limits: WorkflowLimits? = null,
        description: String? = null,
    ) = Workflow(
        id = id,
        name = name,
        domain = "",
        description = description,
        nodes = nodes,
        edges = edges,
        start = start,
        inputs = inputs,
        limits = limits,
        status = WorkflowStatus.ACCEPTED,
        foundAt = AT,
    )

    private fun refused(workflow: Workflow, contains: String) {
        val error = assertFailsWith<IllegalArgumentException> { WorkflowChecks.check(workflow) }
        assertTrue(contains in error.message.orEmpty(), "Expected \"$contains\" in \"${error.message}\"")
    }

    @Test
    fun onlyAWorkflowTheServerPlaysIsChecked() {
        val harness = flow(listOf(node("s", STEP), node("c", CHECK)), name = " ", inputs = emptyList())
        assertFalse(WorkflowChecks.runsOnServer(harness))
        WorkflowChecks.check(harness)
        listOf(agent(), ask(), call("c", "x")).forEach { server ->
            assertTrue(WorkflowChecks.runsOnServer(flow(listOf(node("t", TRIGGER), server))))
        }
    }

    @Test
    fun aWellFormedWorkflowPasses() {
        val text = "Fix {{inputs.branch}} after {{ nodes.q.output }}"
        val full = flow(
            nodes = listOf(
                node("t", TRIGGER),
                node(
                    "a",
                    AGENT,
                    run = NodeRun(
                        instructions = text,
                        deliverable = Deliverable("url", label = "The PR of {{inputs.branch}}", domain = "github.com"),
                    ),
                ).copy(maxVisits = 20),
                node("q", WAIT).copy(
                    wait = WaitSpec(
                        "Ship it?",
                        "Branch {{inputs.branch}}",
                        actions = listOf(WaitAction("ship", "Ship"), WaitAction("hold", "Hold")),
                    ),
                ),
                call("c", "child", "target" to "{{inputs.branch}}"),
            ),
            edges = listOf(
                edge("t", "a"),
                edge("a", "q", "delivered"),
                edge("q", "c", "ship"),
                edge("q", null, "hold"),
            ),
            start = "t",
            limits = WorkflowLimits(maxNodes = 100, maxVisits = 500, maxDepth = 5),
            description = "x".repeat(2_000),
            name = "x".repeat(120),
        )
        WorkflowChecks.check(full)
        WorkflowChecks.check(flow(limits = WorkflowLimits(maxNodes = 1, maxVisits = 1, maxDepth = 0)))
        WorkflowChecks.check(flow(limits = WorkflowLimits()))
    }

    @Test
    fun theHeaderIsChecked() {
        refused(flow(name = "  "), "The name is 1 to 120 characters.")
        refused(flow(name = "x".repeat(121)), "The name is 1 to 120 characters.")
        refused(flow(name = "a\u0007b"), "no control characters")
        refused(flow(description = "x".repeat(2_001)), "The description is at most 2000 characters.")
    }

    @Test
    fun theLimitsAreBounded() {
        refused(flow(limits = WorkflowLimits(maxNodes = 0)), "limits.maxNodes is 1 to 100.")
        refused(flow(limits = WorkflowLimits(maxNodes = 101)), "limits.maxNodes is 1 to 100.")
        refused(flow(limits = WorkflowLimits(maxVisits = 0)), "limits.maxVisits is 1 to 500.")
        refused(flow(limits = WorkflowLimits(maxVisits = 501)), "limits.maxVisits is 1 to 500.")
        refused(flow(limits = WorkflowLimits(maxDepth = -1)), "limits.maxDepth is 0 to 5.")
        refused(flow(limits = WorkflowLimits(maxDepth = 6)), "limits.maxDepth is 0 to 5.")
    }

    // An agent that names no input, so only the inputs under test are judged.
    private fun withInputs(inputs: List<WorkflowInput>) = flow(listOf(agent(instructions = "Fix it")), inputs = inputs)

    @Test
    fun theInputsAreBounded() {
        fun inputs(n: Int) = (1..n).map { WorkflowInput("i$it", "Input $it") }
        WorkflowChecks.check(withInputs(inputs(16)))
        refused(withInputs(inputs(17)), "At most 16 inputs.")
        refused(withInputs(listOf(WorkflowInput("a", "A"), WorkflowInput("a", "B"))), "Input ids are unique.")
        refused(withInputs(listOf(WorkflowInput("1a", "A"))), "Input 1a: an id starts with a letter")
        refused(withInputs(listOf(WorkflowInput("a-b", "A"))), "Input a-b")
        refused(withInputs(listOf(WorkflowInput("a", " "))), "Input a: a label of 1 to 200.")
        refused(withInputs(listOf(WorkflowInput("a", "x".repeat(201)))), "Input a: a label of 1 to 200.")
        refused(withInputs(listOf(WorkflowInput("a", "A", maxLength = 0))), "Input a: maxLength is 1 to 32000.")
        val wide = WorkflowInput("a", "A", maxLength = 32_001)
        refused(withInputs(listOf(wide)), "maxLength is 1 to 32000.")
        val long = WorkflowInput("a", "A", default = "xxx", maxLength = 2)
        refused(withInputs(listOf(long)), "default is longer")
        WorkflowChecks.check(withInputs(listOf(WorkflowInput("a", "A", default = "xx", maxLength = 2))))
    }

    @Test
    fun theGraphIsChecked() {
        refused(flow(listOf(agent("a"), agent("b")), limits = WorkflowLimits(maxNodes = 1)), "1 to 1 nodes.")
        refused(flow(listOf(agent("a"), agent("a"))), "Node ids are unique.")
        refused(flow(start = "nope"), "The start node nope does not exist.")
        refused(flow(listOf(ask("q"), agent("a")), start = "q"), "Node q: the start node cannot ask a person")
        refused(flow(edges = listOf(edge("ghost", null))), "An edge starts from ghost")
        refused(flow(edges = listOf(edge("a", "ghost"))), "Node a: an edge goes to ghost, which is not a node.")
        refused(flow(listOf(agent("a"), agent("b")), listOf(edge("a", null))), "Node b: not reachable from the start.")
        refused(
            flow(listOf(agent("a"), agent("b")), listOf(edge("a", "b"), edge("b", "a"))),
            "No node ends the workflow",
        )
        WorkflowChecks.check(flow(listOf(agent("a"), agent("b")), listOf(edge("a", "b"), edge("b", null))))
        WorkflowChecks.check(flow(listOf(agent("a"), agent("b")), listOf(edge("a", "b")), start = "a"))
    }

    @Test
    fun aNodeHasAnIdATitleAndVisitsInBounds() {
        refused(flow(listOf(agent("a b"))), "an id is letters, digits, _ or - (64 at most).")
        refused(flow(listOf(agent("a").copy(label = " "))), "Node a: a label of 1 to 200 characters.")
        refused(flow(listOf(agent("a").copy(label = "x".repeat(201)))), "Node a: a label of 1 to 200 characters.")
        refused(flow(listOf(agent("a").copy(maxVisits = 0))), "Node a: maxVisits is 1 to 20.")
        refused(flow(listOf(agent("a").copy(maxVisits = 21))), "Node a: maxVisits is 1 to 20.")
    }

    @Test
    fun aTemplateNamesAnInputOrAnotherNodeOutput() {
        refused(flow(listOf(agent(instructions = "Fix {{inputs.nope}}"))), "{{inputs.nope}} is not known")
        refused(flow(listOf(agent(instructions = "Fix {{nodes.x.output}}"))), "{{nodes.x.output}} is not known")
        refused(flow(listOf(agent(instructions = "Fix {{nodes.a.output}}"))), "{{nodes.a.output}} is not known")
        refused(flow(listOf(agent(instructions = "Fix {{ whatever }}"))), "{{whatever}} is not known")
        refused(flow(listOf(agent(instructions = "Fix {{inputs.a.b}}"))), "is not known")
        refused(flow(listOf(agent(instructions = "Fix {{nodes.a.b.output}}"))), "is not known")
        refused(flow(listOf(call("c", "x", "k" to "{{inputs.zzz}}"))), "Node c: {{inputs.zzz}}")
        val own = flow(
            listOf(agent("a"), ask("q", "go").copy(wait = WaitSpec("T", "see {{nodes.q.output}}"))),
            listOf(edge("a", "q"), edge("q", null)),
        )
        refused(own, "Node q: {{nodes.q.output}}")
        assertEquals(
            listOf("inputs.x", "nodes.n.output"),
            NodeChecks.placeholders("{{ inputs.x }} and {{nodes.n.output}}"),
        )
    }

    @Test
    fun anAgentNeedsItsInstructionAndAWellFormedDeliverable() {
        refused(flow(listOf(node("a", AGENT))), "Node a: an agent node run by the server needs run.instructions.")
        refused(flow(listOf(agent(instructions = "  "))), "needs run.instructions.")
        fun delivering(deliverable: Deliverable) =
            flow(listOf(node("a", AGENT, run = NodeRun(instructions = "Do", deliverable = deliverable))))
        refused(delivering(Deliverable("video")), "a deliverable is an artifact, a url, a text or a json.")
        refused(delivering(Deliverable("text", domain = "github.com")), "only a url deliverable has a domain")
        refused(delivering(Deliverable("url", domain = "not a domain")), "only a url deliverable has a domain")
        refused(delivering(Deliverable("text", schema = JsonPrimitive("x"))), "only a json deliverable has a schema.")
        refused(delivering(Deliverable("text", label = "x".repeat(201))), "the deliverable's label is at most 200")
        listOf("artifact", "url", "text").forEach { WorkflowChecks.check(delivering(Deliverable(it))) }
        WorkflowChecks.check(delivering(Deliverable("json", schema = JsonPrimitive("x"))))
        WorkflowChecks.check(delivering(Deliverable("url", domain = "github.com")))
    }

    @Test
    fun aWaitAsksAQuestionWithKnownActions() {
        refused(flow(listOf(agent(), node("q", WAIT)), listOf(edge("a", "q"))), "Node q: a wait node asks a question")
        fun asking(title: String, vararg ids: String) = flow(
            listOf(agent(), node("q", WAIT).copy(wait = WaitSpec(title, actions = ids.map { WaitAction(it, it) }))),
            listOf(edge("a", "q"), edge("q", null)),
        )
        refused(asking(" "), "the question's title is 1 to 200")
        refused(asking("x".repeat(201)), "the question's title is 1 to 200")
        refused(asking("T", *Array(9) { "a$it" }), "at most 8 actions.")
        refused(asking("T", "x", "x"), "action ids are unique.")
        refused(asking("T", "1x"), "an action id is a letter then letters, digits or _.")
        WorkflowChecks.check(asking("T"))
        WorkflowChecks.check(asking("T", *Array(8) { "a$it" }))
    }

    @Test
    fun aSubWorkflowNodeNamesItsWorkflowAndArgumentsAreInputIds() {
        refused(flow(listOf(call("c", null))), "Node c: a workflow node names the workflow to run.")
        refused(flow(listOf(call("c", " "))), "names the workflow to run.")
        refused(flow(listOf(call("c", "x", "bad-name" to "v"))), "argument names are input ids.")
        WorkflowChecks.check(flow(listOf(call("c", "x", "good_name" to "v"))))
    }

    @Test
    fun everyOutcomeOfAServerNodeHasAnEdgeAndEveryEdgeAnOutcome() {
        val pair = listOf(agent("a"), agent("b"))
        refused(flow(pair, listOf(edge("a", "b", "approved"), edge("b", null))), "Node a: no such outcome")
        refused(flow(pair, listOf(edge("a", "b"), edge("a", null, "next"), edge("b", null))), "two edges on the same")
        refused(flow(pair, listOf(edge("a", "b", "delivered"), edge("a", null, "delivered"))), "two edges")
        // `delivered` and the default `next` both take an agent's outcome.
        WorkflowChecks.check(flow(pair, listOf(edge("a", "b", "delivered"), edge("b", null, "next"))))
        WorkflowChecks.check(flow(pair, listOf(edge("a", "b"), edge("b", null))))
        val asking = listOf(agent("a"), ask("q", "ship", "hold"))
        refused(
            flow(asking, listOf(edge("a", "q"), edge("q", null, "ship"))),
            "Node q: say where hold goes",
        )
        WorkflowChecks.check(flow(asking, listOf(edge("a", "q"), edge("q", null, "ship"), edge("q", null))))
        WorkflowChecks.check(
            flow(asking, listOf(edge("a", "q"), edge("q", null, "ship"), edge("q", null, "hold"))),
        )
        // A wait with no action has the one `done`.
        val plain = listOf(agent("a"), ask("q"))
        refused(
            flow(plain, listOf(edge("a", "q"), edge("q", null, "ship"))),
            "Node q: no such outcome on an edge; use done, next.",
        )
        WorkflowChecks.check(flow(plain, listOf(edge("a", "q"), edge("q", null, "done"))))
    }

    private fun checking(vararg edges: WorkflowEdge, condition: Boolean = true) = flow(
        listOf(
            agent("a"),
            node("k", CHECK).copy(
                condition = if (condition) NodeCondition("a", "outcome", NodeConditionOp.EQUALS, "next") else null,
            ),
        ),
        listOf(edge("a", "k")) + edges,
    )

    @Test
    fun aCheckWithAConditionSendsBothItsAnswersSomewhere() {
        WorkflowChecks.check(checking(edge("k", null, "true"), edge("k", null, "false")))
        refused(checking(edge("k", null, "true")), "Node k: say where false goes")
        refused(checking(), "Node k: say where false goes")
        refused(checking(edge("k", null, "true"), edge("k", null, "next")), "Node k: no such outcome")
        refused(checking(edge("k", null), edge("k", null, "false")), "Node k: no such outcome")
        refused(checking(edge("k", null, "true"), edge("k", null, "true"), edge("k", null, "false")), "two edges")
        // Without a condition there is no outcome to check.
        WorkflowChecks.check(checking(edge("k", null), condition = false))
    }

    @Test
    fun theCallersOfAWorkflowAreThoseThatCallItWithAWorkflowNode() {
        val child = flow(listOf(agent("a")), id = "child", name = "Child")
        val parent = flow(listOf(call("c", "child", "branch" to "x")), id = "parent", name = "Parent")
        assertEquals(listOf("parent"), WorkflowChecks.callersOf("child", listOf(child, parent)).map { it.id })
        assertEquals(emptyList(), WorkflowChecks.callersOf("parent", listOf(child, parent)))
        assertEquals(emptyList(), WorkflowChecks.callersOf("child", listOf(parent.copy(id = "child"))))
        val stepCalling = parent.copy(nodes = listOf(node("c", STEP).copy(workflowId = "child")))
        assertEquals(emptyList(), WorkflowChecks.callersOf("child", listOf(stepCalling)))
    }

    private fun calling(id: String, target: String, vararg arguments: Pair<String, String>, maxDepth: Int? = null) =
        flow(
            listOf(call("c", target, *arguments)),
            id = id,
            name = id,
            limits = maxDepth?.let { WorkflowLimits(maxDepth = it) },
            inputs = emptyList(),
        )

    private fun callRefused(workflow: Workflow, others: List<Workflow>, contains: String) {
        val error = assertFailsWith<IllegalArgumentException> {
            CallChecks.check(workflow, others.associateBy { it.id })
        }
        assertTrue(contains in error.message.orEmpty(), "Expected \"$contains\" in \"${error.message}\"")
    }

    private fun leaf(id: String, vararg inputs: WorkflowInput) =
        flow(listOf(agent()), id = id, name = id, inputs = inputs.toList())

    @Test
    fun aSubWorkflowExistsAndIsNotTheWorkflowItself() {
        callRefused(calling("a", "a"), emptyList(), "Node c: a workflow cannot call itself.")
        callRefused(calling("a", "gone"), emptyList(), "Node c: the workflow gone does not exist.")
        CallChecks.check(flow(listOf(agent())), emptyMap())
        // A node that is not a workflow node calls nothing, whatever it names, nor a workflow node naming nothing.
        CallChecks.check(flow(listOf(node("s", STEP).copy(workflowId = "gone"), call("n", null))), emptyMap())
    }

    @Test
    fun aCallLoopIsRefusedWithItsPath() {
        callRefused(
            calling("a", "b"),
            listOf(calling("b", "c"), calling("c", "a")),
            "Workflow calls loop back to this one (a -> b -> c -> a).",
        )
        // A loop among the others, that does not pass through this workflow, is refused too.
        callRefused(
            calling("a", "d"),
            listOf(calling("d", "e"), calling("e", "d")),
            "Workflow calls loop (a -> d -> e -> d).",
        )
    }

    @Test
    fun theChainIsNoDeeperThanTheLimit() {
        val chain = listOf(calling("b", "c"), calling("c", "d"), calling("d", "e"), leaf("e"))
        callRefused(calling("a", "b"), chain, "nested 4 deep; this workflow allows 3.")
        CallChecks.check(calling("a", "b", maxDepth = 5), chain.associateBy { it.id })
        callRefused(calling("a", "e", maxDepth = 0), chain, "nested 1 deep; this workflow allows 0.")
        // Limits that leave the depth out keep the default.
        callRefused(calling("a", "b").copy(limits = WorkflowLimits(maxNodes = 10)), chain, "this workflow allows 3.")
        // A workflow that went missing under another one ends the chain there.
        CallChecks.check(calling("a", "b"), listOf(calling("b", "gone")).associateBy { it.id })
    }

    @Test
    fun theArgumentsOfACallCoverTheRequiredInputsAndNameNoUnknownOne() {
        val required = WorkflowInput("t", "T", required = true)
        callRefused(calling("a", "b"), listOf(leaf("b", required)), "Node c: workflow b needs the input t.")
        callRefused(calling("a", "b", "zz" to "v"), listOf(leaf("b")), "Node c: workflow b has no such input.")
        val others = { input: WorkflowInput -> listOf(leaf("b", input)).associateBy { it.id } }
        CallChecks.check(calling("a", "b", "t" to "v"), others(required))
        CallChecks.check(calling("a", "b"), others(required.copy(default = "x")))
        CallChecks.check(calling("a", "b"), others(required.copy(required = false)))
    }
}
