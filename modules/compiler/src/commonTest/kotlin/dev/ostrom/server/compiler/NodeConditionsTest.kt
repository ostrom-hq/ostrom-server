// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.core.api.models.NodeCondition
import dev.ostrom.core.api.models.NodeConditionOp
import dev.ostrom.core.api.models.NodeVisit
import dev.ostrom.core.api.models.WorkflowEdge
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowNodeKind
import dev.ostrom.server.compiler.domain.services.ConditionVerdict
import dev.ostrom.server.compiler.domain.services.LinearPattern
import dev.ostrom.server.compiler.domain.services.NodeConditions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NodeConditionsTest {
    private fun checked(
        condition: NodeCondition,
        kind: WorkflowNodeKind = WorkflowNodeKind.CHECK,
        on: String? = "true",
    ) {
        val nodes = listOf(
            node("ask", WorkflowNodeKind.WAIT),
            WorkflowNode("c", kind, "C", condition = condition),
            node("done", WorkflowNodeKind.TRIGGER),
        )
        val edges = listOf(
            WorkflowEdge("ask", "c"),
            WorkflowEdge("c", "done", on = on),
            WorkflowEdge("c", null, on = "false"),
        )
        NodeConditions.check(workflow("w", nodes).copy(edges = edges))
    }

    private fun refusal(
        condition: NodeCondition,
        kind: WorkflowNodeKind = WorkflowNodeKind.CHECK,
        on: String? = "true",
    ) = assertFailsWith<IllegalArgumentException> { checked(condition, kind, on) }.message

    private fun visit(nodeId: String, outcome: String? = "ship", output: String? = null) =
        NodeVisit(1, nodeId, WorkflowNodeKind.WAIT, "done", outcome, output, startedAt = AT)

    private fun holds(condition: NodeCondition, vararg visits: NodeVisit) =
        NodeConditions.evaluate(condition, visits.toList())

    @Test
    fun aWellFormedConditionOfACheckNodeIsSavedAndAWorkflowWithoutConditionsToo() {
        checked(NodeCondition("ask", "outcome", NodeConditionOp.EQUALS, "ship"))
        checked(NodeCondition("ask", "output.note", NodeConditionOp.EXISTS), on = "false")
        checked(NodeCondition("ask", "output.a.b-c.d_e", NodeConditionOp.MATCHES, "^go+$"))
        checked(NodeCondition("c", "output", NodeConditionOp.NOT_EQUALS, ""))
        NodeConditions.check(workflow("w", listOf(node("t", WorkflowNodeKind.TRIGGER))))
    }

    @Test
    fun aConditionOutOfItsFormIsRefusedWithItsNodeAndItsReason() {
        val equals = NodeCondition("ask", "outcome", NodeConditionOp.EQUALS, "ship")
        val at = "node c: condition"
        assertEquals("$at: only a check node has a condition.", refusal(equals, WorkflowNodeKind.GATE))
        assertEquals("$at.step: gone is not a node of the workflow.", refusal(equals.copy(step = "gone")))
        val form = "$at.path: outcome, output or output.<key>[.<key>…], at most 10 keys of 1 to 64 letters, digits, " +
            "_ or -."
        assertEquals(form, refusal(equals.copy(path = "outputs")))
        assertEquals(form, refusal(equals.copy(path = "output.a b")))
        assertEquals(form, refusal(equals.copy(path = "output" + ".k".repeat(NodeConditions.MAX_KEYS + 1))))
        assertEquals(form, refusal(equals.copy(path = "output." + "k".repeat(65))))
        assertEquals(form, refusal(equals.copy(path = "output" + ".${"k".repeat(25)}".repeat(8))))
        val unknown = equals.copy(op = NodeConditionOp.UNKNOWN)
        assertEquals("$at.op: equals, notEquals, exists or matches.", refusal(unknown))
        val value = "$at.value: required for equals, notEquals and matches, absent for exists."
        assertEquals(value, refusal(equals.copy(value = null)))
        assertEquals(value, refusal(equals.copy(op = NodeConditionOp.EXISTS)))
        assertEquals(
            "$at.value: \\1 at position 2 is not allowed (no backreference).",
            refusal(equals.copy(op = NodeConditionOp.MATCHES, value = "\\1")),
        )
        val branches = "$at: every edge out of the node says on true or on false."
        assertEquals(branches, refusal(equals, on = null))
        assertEquals(branches, refusal(equals, on = "next"))
    }

    @Test
    fun aConditionComparesTheTextOfTheLastVisitOfItsStep() {
        val outcome = NodeCondition("ask", "outcome", NodeConditionOp.EQUALS, "ship")
        val first = visit("ask", "hold")
        assertEquals(ConditionVerdict(true), holds(outcome, first, visit("other", "hold"), visit("ask", "ship")))
        assertEquals(ConditionVerdict(false), holds(outcome, first))
        val not = outcome.copy(op = NodeConditionOp.NOT_EQUALS)
        assertEquals(listOf(true, false), listOf(holds(not, first), holds(not, visit("ask"))).map { it.holds })
        val output = NodeCondition("ask", "output", NodeConditionOp.MATCHES, "^built [0-9]+$")
        assertEquals(ConditionVerdict(true), holds(output, visit("ask", output = "built 12")))
        assertEquals(ConditionVerdict(false), holds(output, visit("ask", output = "built")))
    }

    @Test
    fun aValueOfTheOutputReadAsAJsonObjectIsComparedAsItsText() {
        val json = """{"note":"go","n":1.0,"ok":true,"none":null,"deep":{"k":["a",1]},"list":[1]}"""
        fun read(path: String, value: String) = holds(
            NodeCondition("ask", path, NodeConditionOp.EQUALS, value),
            visit("ask", output = json),
        ).holds
        assertEquals(true, read("output.note", "go"))
        assertEquals(true, read("output.n", "1.0"))
        assertEquals(false, read("output.n", "1"))
        assertEquals(true, read("output.ok", "true"))
        assertEquals(true, read("output.deep.k", """["a",1]"""))
        assertEquals(true, read("output.deep", """{"k":["a",1]}"""))
        fun exists(path: String, output: String? = json) =
            holds(NodeCondition("ask", path, NodeConditionOp.EXISTS), visit("ask", output = output)).holds
        assertEquals(
            listOf(true, false, false, false, false, false, false),
            listOf(
                exists("output.note"), exists("output.none"), exists("output.gone"), exists("output.list.k"),
                exists("output.note.k"), exists("output.note", "not json"), exists("output.note", null),
            ),
        )
    }

    @Test
    fun aConditionWithoutValueStepOrOperatorDoesNotEvaluateAndSaysWhy() {
        val equals = NodeCondition("ask", "outcome", NodeConditionOp.EQUALS, "ship")
        assertEquals(
            "The check node has no condition: the server plays a check node by its condition only.",
            NodeConditions.evaluate(null, listOf(visit("ask"))).reason,
        )
        // A step the run did not visit has no value: `exists` is false, `equals` does not evaluate.
        assertEquals(ConditionVerdict(false), holds(equals.copy(op = NodeConditionOp.EXISTS, value = null)))
        assertEquals("The condition reads outcome of step ask, which has no value.", holds(equals).reason)
        val long = visit("ask", output = "a".repeat(LinearPattern.MAX_TEXT + 1))
        assertEquals(
            "The condition reads output of step ask, longer than 10000 characters.",
            holds(equals.copy(path = "output"), long).reason,
        )
        val longest = "a".repeat(LinearPattern.MAX_TEXT)
        assertEquals(true, holds(equals.copy(path = "output", value = longest), visit("ask", output = longest)).holds)
        // Never saved so (the save refuses them), but a stored run's copy is read defensively.
        val shown = "The condition's equals has no value to compare with."
        assertEquals(shown, holds(equals.copy(value = null), visit("ask")).reason)
        assertEquals(
            "The condition's  has no value to compare with.",
            holds(equals.copy(op = NodeConditionOp.UNKNOWN), visit("ask")).reason,
        )
        assertEquals(
            "The condition's pattern: a ( is never closed.",
            holds(equals.copy(op = NodeConditionOp.MATCHES, value = "("), visit("ask")).reason,
        )
    }
}
