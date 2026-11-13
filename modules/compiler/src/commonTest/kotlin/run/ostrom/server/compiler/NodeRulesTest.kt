// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.core.api.models.AgentEvent
import run.ostrom.core.api.models.ArtifactType
import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.NodeRunOn
import run.ostrom.core.api.models.NodeSuggestions
import run.ostrom.core.api.models.Produced
import run.ostrom.core.api.models.RunLocation
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind.AGENT
import run.ostrom.core.api.models.WorkflowNodeKind.CHECK
import run.ostrom.core.api.models.WorkflowNodeKind.GATE
import run.ostrom.core.api.models.WorkflowNodeKind.NOTIFY
import run.ostrom.core.api.models.WorkflowNodeKind.STEP
import run.ostrom.core.api.models.WorkflowNodeKind.TRIGGER
import run.ostrom.core.api.models.WorkflowNodeKind.WAIT
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.services.NodeProduces
import run.ostrom.server.compiler.domain.services.NodeRules
import run.ostrom.server.compiler.domain.services.SuggestionField
import run.ostrom.server.compiler.domain.services.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeRulesTest {
    private val all = NodeSuggestions(
        command = text("npm test"),
        on = texts("push-main"),
        actions = texts("deploy"),
        channel = text("slack:#ops"),
        ruleIds = texts("r1", "r2"),
    )

    @Test
    fun aSuggestionIsOpenOnlyWhenPresentNotDismissedAndWithoutAHumanValue() {
        val bare = node("n", CHECK)
        SuggestionField.entries.forEach { assertFalse(NodeRules.openSuggestion(bare, it), it.wire) }
        val open = node("n", CHECK, suggested = all)
        SuggestionField.entries.forEach { assertTrue(NodeRules.openSuggestion(open, it), it.wire) }
        val dismissed = node("n", CHECK, suggested = all.copy(dismissed = SuggestionField.entries.map { it.wire }))
        SuggestionField.entries.forEach { assertFalse(NodeRules.openSuggestion(dismissed, it), it.wire) }
        val human = node(
            "n",
            CHECK,
            run = NodeRun(command = "make", channel = "log:x", on = NodeRunOn(patterns = listOf("x"))),
            actions = emptyList(),
            ruleIds = listOf("r1", "r2"),
            suggested = all,
        )
        SuggestionField.entries.forEach { assertFalse(NodeRules.openSuggestion(human, it), it.wire) }
        val blank = node("n", CHECK, run = NodeRun(command = "  ", channel = " ", on = NodeRunOn()), suggested = all)
        assertTrue(NodeRules.openSuggestion(blank, SuggestionField.COMMAND))
        assertTrue(NodeRules.openSuggestion(blank, SuggestionField.CHANNEL))
        assertTrue(NodeRules.openSuggestion(blank, SuggestionField.ON))
        val oneLinked = node("n", CHECK, ruleIds = listOf("r1"), suggested = all)
        assertTrue(NodeRules.openSuggestion(oneLinked, SuggestionField.RULE_IDS))
    }

    @Test
    fun aTriggerIsTheNodesThenTheSuggestionThenALinkedGateThenItsWords() {
        val gate = node("g", GATE, actions = listOf(action("push-main", patterns = listOf("p1"))))
        val declared = node("c", CHECK, run = on("deploy"))
        val wf = workflow("w", listOf(declared, gate), chain("c", "g"))
        assertEquals(Trigger("node", null, listOf("deploy"), emptyList()), NodeRules.triggerOf(declared, wf))
        val patternsOnly = node("c", CHECK, run = NodeRun(on = NodeRunOn(patterns = listOf("x"))))
        assertEquals(Trigger("node", null, emptyList(), listOf("x")), NodeRules.triggerOf(patternsOnly, wf))
        val suggested = node("c", CHECK, suggested = NodeSuggestions(on = texts("merge")))
        assertEquals(Trigger("suggested", null, listOf("merge"), emptyList()), NodeRules.triggerOf(suggested, wf, true))
        assertEquals(Trigger("gate", "g", listOf("push-main"), listOf("p1")), NodeRules.triggerOf(suggested, wf))
        val notify = node("n", NOTIFY)
        val up = workflow("w", listOf(gate, notify), chain("g", "n"))
        assertEquals(Trigger("gate", "g", listOf("push-main"), listOf("p1")), NodeRules.triggerOf(notify, up))
        val bareGate = node("g", GATE)
        val noActions = workflow("w", listOf(node("c", CHECK), bareGate), chain("c", "g"))
        assertEquals(Trigger("gate", "g", emptyList(), emptyList()), NodeRules.triggerOf(noActions.nodes[0], noActions))
    }

    @Test
    fun theGraphWalkStopsAtSixStepsAndNeverLoops() {
        val ids = (1..7).map { "s$it" }
        val nodes = listOf(node("c", CHECK)) + ids.map { node(it, STEP) } + node("g", GATE)
        val far = workflow("w", nodes, chain("c", *ids.toTypedArray(), "g"))
        assertEquals("none", NodeRules.triggerOf(nodes[0], far).from)
        val loop = workflow("w", listOf(node("c", CHECK), node("s", STEP)), listOf("c" to "s", "s" to "c", "s" to null))
        assertEquals("none", NodeRules.triggerOf(loop.nodes[0], loop).from)
        val twoGates = workflow(
            "w",
            listOf(node("c", CHECK), node("g1", GATE), node("g2", GATE)),
            listOf("c" to "g1", "c" to "g2"),
        )
        assertEquals("g1", NodeRules.triggerOf(twoGates.nodes[0], twoGates).gateNodeId)
    }

    @Test
    fun theWordsGiveAClassInTheirOrder() {
        val cases = mapOf(
            "Merge the PR" to "merge",
            "Deploy" to "deploy",
            "helm upgrade" to "deploy",
            "Cut a release" to "push-tag",
            "push to main" to "push-main",
            "push the branch" to "push-any",
            "commit" to "commit",
            "Run migrations" to "migration",
        )
        cases.forEach { (label, cls) ->
            val check = node("c", CHECK, label = label)
            val trigger = NodeRules.triggerOf(check, workflow("w", listOf(check)))
            assertEquals(Trigger("words", null, listOf(cls), emptyList()), trigger, label)
        }
        val detail = node("c", CHECK, label = "Tests", detail = "before the rollout")
        assertEquals(listOf("deploy"), NodeRules.triggerOf(detail, workflow("w", listOf(detail))).classes)
        val none = node("c", CHECK, label = "Tests")
        val nothing = NodeRules.triggerOf(none, workflow("w", listOf(none)))
        assertEquals(Trigger("none", null, emptyList(), emptyList()), nothing)
    }

    private fun produced(vararg nodes: WorkflowNode, status: WorkflowStatus = WorkflowStatus.ACCEPTED) =
        workflow("w", nodes.toList(), status = status).let { wf -> nodes.map { NodeProduces.produces(it, wf) } }

    @Test
    fun simpleNodesProduceTheirArtifacts() {
        val simple = produced(node("t", TRIGGER), node("s", STEP), node("a", AGENT), node("x", WAIT))
        val (trigger, step, agent) = simple
        val wait = simple[3]
        assertEquals(listOf(ArtifactType.CONTEXT), trigger.map { it.type })
        assertEquals(AgentEvent.SESSION_START, trigger[0].event)
        assertEquals("instructions and linked rules, loaded when the agent needs them", step[0].note)
        assertEquals(listOf(ArtifactType.SUBAGENT, ArtifactType.MCP), agent.map { it.type })
        assertEquals(emptyList(), wait)
        val one = node("s", STEP, suggested = NodeSuggestions(ruleIds = texts("r1")))
        val two = node("a", AGENT, suggested = NodeSuggestions(ruleIds = texts("r1", "r2")))
        val (oneRule, twoRules) = produced(one, two)
        assertEquals("1 rule suggested by the analysis: not in the skill until someone links them", oneRule[0].note)
        assertEquals("2 rules suggested by the analysis: not in the skill until someone links them", twoRules[0].note)
    }

    @Test
    fun aProposedWorkflowProducesNoSkillAndNoSubAgentUntilAPersonAcceptsIt() {
        val nodes = arrayOf(
            node("s", STEP),
            node("a", AGENT),
            node("c", CHECK, label = "x"),
            node("k", CHECK, label = "y", run = NodeRun(command = "make")),
            node("t", TRIGGER),
        )
        val proposed = produced(*nodes, status = WorkflowStatus.PROPOSED)
        val (step, agent, bare) = proposed
        val scripted = proposed[3]
        val trigger = proposed[4]
        assertEquals(emptyList(), step)
        assertEquals(emptyList(), agent)
        assertEquals(emptyList(), bare, "A check with no command only asks for a skill")
        assertEquals(listOf(ArtifactType.HOOK, ArtifactType.SCRIPT), scripted.map { it.type })
        assertEquals(listOf(ArtifactType.CONTEXT), trigger.map { it.type })
        val (acceptedStep, acceptedAgent) = produced(*nodes)
        assertEquals(listOf(ArtifactType.SKILL), acceptedStep.map { it.type })
        assertEquals(listOf(ArtifactType.SUBAGENT, ArtifactType.MCP), acceptedAgent.map { it.type })
    }

    @Test
    fun aCheckProducesAHookAndAScriptOnlyWithACommand() {
        val suggestedFound = node("c1", CHECK, label = "push", suggested = NodeSuggestions(command = text("npm test")))
        val suggestedNone = node("c2", CHECK, label = "x", suggested = NodeSuggestions(command = text("npm test")))
        val noCommand = node("c3", CHECK, label = "x")
        val withRules = node("c4", CHECK, label = "x", suggested = NodeSuggestions(ruleIds = texts("r")))
        val untriggered = node("c5", CHECK, label = "x", run = NodeRun(command = "make", where = RunLocation.OSTROM))
        val suggestedOn = node(
            "c6",
            CHECK,
            label = "x",
            run = NodeRun(command = "make"),
            suggested = NodeSuggestions(on = texts("deploy", "merge")),
        )
        val triggered = node("c7", CHECK, run = NodeRun(command = "make", on = NodeRunOn(listOf("deploy"))))
        val results = produced(suggestedFound, suggestedNone, noCommand, withRules, untriggered, suggestedOn, triggered)
        assertEquals("suggested command: logged, never blocking", results[0][1].note)
        assertEquals(listOf(true, true), results[0].drop(1).map { it.suggested })
        assertEquals("suggested command, no trigger found", results[1][1].note)
        val noCommandNote = "no command: the agent is asked to check, nothing runs"
        assertEquals(listOf(Produced(ArtifactType.SKILL, note = noCommandNote)), results[2])
        assertEquals("1 rule suggested by the analysis: not in the skill until someone links them", results[3][0].note)
        assertEquals("no trigger found: set \"Runs before\"", results[4][0].note)
        assertEquals(RunLocation.OSTROM, results[4][1].where)
        assertEquals(
            "no trigger set: the analysis suggests deploy, merge, used only once someone takes it",
            results[5][0].note,
        )
        assertEquals(
            listOf(
                Produced(ArtifactType.HOOK, event = AgentEvent.PRE_TOOL_USE, shadow = false),
                Produced(ArtifactType.SCRIPT, where = RunLocation.WORKSTATION, shadow = false),
            ),
            results[6],
        )
        val (shadow) = produced(triggered, status = WorkflowStatus.PROPOSED)
        assertEquals("proposed workflow: logged, never run", shadow[1].note)
    }

    @Test
    fun aDismissedSuggestionLeavesNoNote() {
        val check = node(
            "c",
            CHECK,
            label = "x",
            run = NodeRun(command = "make"),
            suggested = NodeSuggestions(on = texts("deploy"), dismissed = listOf("on")),
        )
        val gate = node("g", GATE, suggested = NodeSuggestions(actions = texts("merge"), dismissed = listOf("actions")))
        val (checked, gated) = produced(check, gate)
        assertEquals("no trigger found: set \"Runs before\"", checked[0].note, "the fallback, not the dismissed one")
        assertNull(gated[0].note)
    }

    @Test
    fun aGateProducesAHookAnApprovalAndAReceipt() {
        val plain = node("g1", GATE)
        val suggested = node("g2", GATE, suggested = NodeSuggestions(actions = texts("deploy", "merge")))
        val suggestedNone = node("g3", GATE, suggested = NodeSuggestions(actions = texts()))
        val (a, b, c) = produced(plain, suggested, suggestedNone)
        assertEquals(listOf(ArtifactType.HOOK, ArtifactType.APPROVAL, ArtifactType.RECEIPT), a.map { it.type })
        assertEquals(null, a[1].note)
        assertEquals(
            "actions suggested by the analysis (deploy, merge): the bridge ignores them until someone confirms",
            b[0].note,
        )
        assertEquals(
            "actions suggested by the analysis (none): the bridge ignores them until someone confirms",
            c[0].note,
        )
        val (shadow) = produced(plain, status = WorkflowStatus.PROPOSED)
        assertEquals("proposed workflow: logged, never blocking", shadow[1].note)
    }

    @Test
    fun aNotifyIsSentOnlyToAChannelSomeoneChose() {
        val chosen = node("n1", NOTIFY, label = "push", run = NodeRun(channel = "log:x"))
        val suggested = node("n2", NOTIFY, label = "x", suggested = NodeSuggestions(channel = text("slack:#ops")))
        val none = node("n3", NOTIFY, label = "x")
        val (a, b, c) = produced(chosen, suggested, none)
        assertEquals(listOf(false, false), a.map { it.shadow })
        assertEquals(null, a[1].note)
        assertEquals("channel suggested (slack:#ops): logged, never sent until someone chooses it", b[1].note)
        assertEquals(true, b[1].suggested)
        assertEquals("no trigger found: set \"Runs after\"", b[0].note)
        assertEquals("no channel chosen: logged, never sent", c[1].note)
        val (shadow) = produced(chosen, status = WorkflowStatus.PROPOSED)
        assertEquals("proposed workflow: logged, never sent", shadow[1].note)
    }
}
