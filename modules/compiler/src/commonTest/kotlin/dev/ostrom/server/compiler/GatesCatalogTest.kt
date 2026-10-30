// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.core.api.models.GateAction
import dev.ostrom.core.api.models.WorkflowNodeKind.GATE
import dev.ostrom.core.api.models.WorkflowNodeKind.STEP
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.ActionClasses
import dev.ostrom.server.compiler.domain.services.GatesCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatesCatalogTest {
    private val merge = ActionClasses.builtIn.first { it.cls == "merge" }

    @Test
    fun theShortNamesOfARepositoryAreItsTailOfThreeLettersOrMore() {
        assertEquals(listOf("controlresell-core", "core"), GatesCatalog.aliases("ControlResell-core"))
        assertEquals(listOf("api"), GatesCatalog.aliases("api"))
        assertEquals(listOf("ab-cd"), GatesCatalog.aliases("ab-cd"))
        assertTrue(GatesCatalog.mentions("ship core now", "core"))
        assertFalse(GatesCatalog.mentions("ship core-api now", "core"))
        assertTrue(GatesCatalog.covers(emptyList(), null))
        assertTrue(GatesCatalog.covers(listOf("CORE"), "acme-core"))
        assertFalse(GatesCatalog.covers(listOf("core"), null))
        assertFalse(GatesCatalog.covers(listOf("web"), "acme-core"))
    }

    @Test
    fun aDeclaredGateWinsDecidedFirstThenTheOneThatNamesTheRepository() {
        val proposed = workflow(
            "w1",
            listOf(node("g1", GATE, actions = listOf(action("merge", listOf("acme-api"))))),
            status = WorkflowStatus.PROPOSED,
        )
        val decided = workflow(
            "w2",
            listOf(node("g2", GATE, actions = listOf(action("merge")), approvers = listOf("rev-b"))),
        )
        val named = workflow("w3", listOf(node("g3", GATE, actions = listOf(action("merge", listOf("acme-api"))))))
        val other = workflow("w4", listOf(node("g4", GATE, actions = listOf(action("deploy")))))
        val gate = GatesCatalog(listOf(proposed, decided, named, other), "ops").gateFor(merge, "acme-api")
        assertEquals("g3", gate?.nodeId)
        assertEquals(listOf("ops"), gate?.approvers)
        assertEquals("production approver in Settings", gate?.reason)
        assertEquals("declared", gate?.basis)
        assertEquals(true, gate?.enforce)
        val first = GatesCatalog(listOf(decided, named), null).gateFor(merge, "acme-api")
        assertEquals("g3", first?.nodeId)
        assertEquals(emptyList(), first?.approvers)
        val onlyProposed = GatesCatalog(listOf(proposed), null).gateFor(merge, "acme-api")
        assertEquals(false, onlyProposed?.enforce)
        assertEquals("proposed", onlyProposed?.status)
        val reasoned = workflow(
            "w5",
            listOf(node("g5", GATE, actions = listOf(action("merge"))).copy(approverReason = "owners")),
        )
        assertEquals("owners", GatesCatalog(listOf(reasoned), null).gateFor(merge, null)?.reason)
        assertEquals(
            "gate approvers of the workflow",
            GatesCatalog(listOf(decided), null).gateFor(merge, null)?.reason,
        )
    }

    @Test
    fun aGateThatDeclaredNothingIsGuessedFromItsWords() {
        val step = node("s", STEP, label = "review")
        val guessed = workflow("w2", listOf(node("g", GATE, label = "Approve the merge"), step), name = "acme-api")
        val weaker = workflow(
            "w1",
            listOf(node("g0", GATE, label = "approve merge"), step),
            status = WorkflowStatus.PROPOSED,
        )
        val few = workflow("w0", listOf(node("g9", GATE, label = "merge")))
        val gate = GatesCatalog(listOf(weaker, guessed, few), null).gateFor(merge, "acme-api")
        assertEquals("g", gate?.nodeId)
        assertEquals("guessed", gate?.basis)
        assertNull(GatesCatalog(listOf(few), null).gateFor(merge, "acme-api"))
        // At equal scores: a decided workflow first, then the lower id.
        val a = workflow("a", listOf(node("ga", GATE, label = "approve merge review")))
        val b = workflow("b", listOf(node("gb", GATE, label = "approve merge review")))
        val p = workflow(
            "c",
            listOf(node("gc", GATE, label = "approve merge review")),
            status = WorkflowStatus.PROPOSED,
        )
        assertEquals("ga", GatesCatalog(listOf(b, a), null).gateFor(merge, null)?.nodeId)
        assertEquals("ga", GatesCatalog(listOf(a, b), null).gateFor(merge, null)?.nodeId)
        // 4 + 1 (a word elsewhere) + 1 (decided) against 6 + 0 (proposed).
        val d = workflow("e", listOf(node("ge", GATE, label = "approve merge"), node("s", STEP, label = "review")))
        assertEquals("ge", GatesCatalog(listOf(p, d), null).gateFor(merge, null)?.nodeId)
        assertEquals("ge", GatesCatalog(listOf(d, p), null).gateFor(merge, null)?.nodeId)
    }

    @Test
    fun theCatalogAddsTheCustomActionsAndTheDeclaredPatterns() {
        val custom = GateAction("db-drop", "", listOf("\\bdrop\\b"), emptyList())
        val tooled = GateAction("ship", "Ship", listOf("ship"), emptyList(), tools = listOf("mcp"))
        val empty = GateAction("nothing", "Nothing", emptyList(), emptyList())
        val again = GateAction("db-drop", "Again", listOf("other"), emptyList())
        val builtIn = GateAction("merge", "Merge", listOf("my-merge"), listOf("web"))
        val actions = listOf(custom, tooled, empty, again, builtIn, action("deploy"))
        val wf = workflow("w", listOf(node("g", GATE, actions = actions)))
        val catalog = GatesCatalog(listOf(wf), null).catalog("acme-api")
        val classes = catalog.map { it.action.cls }
        assertEquals(ActionClasses.builtIn.map { it.cls } + listOf("db-drop", "ship"), classes)
        val drop = catalog.first { it.action.cls == "db-drop" }
        assertEquals("db-drop", drop.action.label)
        assertEquals(listOf("Bash"), drop.action.tools)
        assertEquals(listOf("mcp"), catalog.first { it.action.cls == "ship" }.action.tools)
        assertEquals(merge.patterns, catalog.first { it.action.cls == "merge" }.action.patterns)
        val web = GatesCatalog(listOf(wf), null).catalog("web")
        assertEquals(listOf("my-merge"), web.first { it.action.cls == "merge" }.action.patterns)
    }
}
