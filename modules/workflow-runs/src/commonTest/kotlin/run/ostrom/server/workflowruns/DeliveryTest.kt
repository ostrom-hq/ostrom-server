// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.Deliverable
import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.workflowruns.domain.engine.AgentRequests
import run.ostrom.server.workflowruns.domain.engine.NodePlayers
import run.ostrom.server.workflowruns.domain.engine.PlayRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.RunMoves
import run.ostrom.server.workflowruns.domain.engine.RunTemplates
import run.ostrom.server.workflowruns.domain.engine.failureCodeOf
import run.ostrom.server.workflowruns.domain.engine.sessionOf
import run.ostrom.server.workflowruns.domain.models.InvalidDeliverableException
import run.ostrom.server.workflowruns.domain.models.NothingToDeliverException
import run.ostrom.server.workflowruns.domain.models.PlayedRun
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.models.RunNotFoundException
import run.ostrom.server.workflowruns.domain.models.RunRef
import run.ostrom.server.workflowruns.domain.models.TodoOpenException
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.services.AgentRequest
import run.ostrom.server.workflowruns.domain.services.AgentState
import run.ostrom.server.workflowruns.domain.services.GateOpening
import run.ostrom.server.workflowruns.domain.services.GateRequest
import run.ostrom.server.workflowruns.domain.services.GateState
import run.ostrom.server.workflowruns.domain.services.RunAgents
import run.ostrom.server.workflowruns.domain.services.RunGates
import run.ostrom.server.workflowruns.domain.services.RunTodos
import run.ostrom.server.workflowruns.domain.usecases.DeliverRunUseCaseImpl
import run.ostrom.server.workflowruns.fakes.FakeChildren
import run.ostrom.server.workflowruns.fakes.FakeWaits
import run.ostrom.server.workflowruns.fakes.FixedClock
import run.ostrom.server.workflowruns.fakes.ORG
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An agent node ends by delivering (decision `workflows-one-system`, section 2 "Delivery"): the templates of a run's
 * texts, the agent's first message, the engine's end of an agent visit, and `deliverRun`, on fakes.
 */
class DeliveryTest {
    private val clock = FixedClock()

    /** The runs in memory: a change is written only on the run as read, like the database's. */
    private class Runs(var writes: Boolean = true) : RunEngineRepository {
        val rows = mutableMapOf<String, PlayedRun>()
        val changes = mutableListOf<RunChange>()

        override suspend fun claimDue(owner: String, now: Instant, until: Instant, limit: Int): List<RunRef> =
            rows.keys.map { RunRef(ORG, it) }

        override suspend fun played(orgId: Uuid, id: String): PlayedRun? = rows[id]

        override suspend fun write(orgId: Uuid, change: RunChange): Boolean {
            val stored = rows[change.next.id]?.takeIf { writes && it.run == change.expected }
            stored?.let {
                rows[change.next.id] = it.copy(run = change.next)
                changes += change
            }
            return stored != null
        }

        override suspend fun release(orgId: Uuid, id: String, owner: String, nextAt: Instant?) = Unit
    }

    private class Agents(var state: AgentState? = AgentState.WORKING) : RunAgents {
        val started = mutableListOf<AgentRequest>()

        override suspend fun start(orgId: Uuid, request: AgentRequest): String? {
            started += request
            return null
        }

        override suspend fun state(orgId: Uuid, sessionId: Uuid): AgentState? = state

        override suspend fun stop(orgId: Uuid, sessionId: Uuid) = Unit
    }

    private object NoGates : RunGates {
        override suspend fun open(orgId: Uuid, request: GateRequest): GateOpening = error("No gate here.")

        override suspend fun state(orgId: Uuid, gateId: Uuid): GateState = error("No gate here.")
    }

    private class Todos(var refusal: String? = null) : RunTodos {
        val asked = mutableListOf<String>()

        override suspend fun refusal(orgId: Uuid, sessionId: String): String? {
            asked += sessionId
            return refusal
        }
    }

    private val runs = Runs()
    private val agents = Agents()
    private val play = PlayRunUseCaseImpl(runs, NodePlayers(agents, NoGates, FakeWaits(), FakeChildren()), clock)

    private fun run(
        status: RunStatus = RunStatus.RUNNING,
        visits: List<NodeVisit> = emptyList(),
        startedBy: String = "ada",
    ) = WorkflowRun(
        id = RUN,
        workflowId = "ship",
        workflowName = "Ship",
        workflowVersion = 1,
        status = status,
        startedBy = startedBy,
        source = "manual",
        inputs = mapOf("branch" to "main"),
        visits = visits,
        startedAt = AT,
    )

    private fun visit(seq: Int, node: String, kind: WorkflowNodeKind, status: String, output: String? = null) =
        NodeVisit(seq, node, kind, status, outcome = null, output = output, startedAt = AT)

    private fun stored(graph: RunGraph, run: WorkflowRun = run()): WorkflowRun {
        runs.rows[RUN] = PlayedRun(run, graph)
        return run
    }

    private suspend fun played(): WorkflowRun {
        play.play(RunRef(ORG, RUN), OWNER)
        return runs.rows.getValue(RUN).run
    }

    @Test
    fun aTemplateReadsTheInputsAndTheLatestFinishedOutputOfEachNodeAndNothingElse() {
        val visits = listOf(
            visit(1, "plan", WorkflowNodeKind.AGENT, RunMoves.DONE, "first plan"),
            visit(2, "t", WorkflowNodeKind.TRIGGER, RunMoves.DONE),
            visit(3, "plan", WorkflowNodeKind.AGENT, RunMoves.DONE, "second plan"),
            visit(4, "fix", WorkflowNodeKind.AGENT, RunMoves.FAILED, "broken"),
        )
        val run = run(visits = visits)
        assertEquals(mapOf("plan" to "second plan"), RunTemplates.outputs(visits))
        val text = "On {{ inputs.branch }}: {{nodes.plan.output}}|{{nodes.fix.output}}|{{inputs.none}}|" +
            "{{inputs}}|{{nodes.plan.title}}|{{secrets.x}}|{{nodes.t.output}}"
        assertEquals("On main: second plan||||||", RunTemplates.render(text, run))
        // Control characters go, line breaks and tabs stay; a value is plain text, never a replacement pattern.
        val dollar = run.copy(inputs = mapOf("x" to "$1"))
        assertEquals("a\nb\tc$1", RunTemplates.render("a\u0000\n\u001bb\tc{{inputs.x}}", dollar))
        val long = RunTemplates.render("x".repeat(RunTemplates.MAX_TEXT + 1), run)
        assertEquals(RunTemplates.MAX_TEXT, long.length)
        assertTrue(long.endsWith("x\n[truncated]"), long.takeLast(20))
        assertEquals("x".repeat(RunTemplates.MAX_TEXT), RunTemplates.cut("x".repeat(RunTemplates.MAX_TEXT)))
    }

    @Test
    fun theAgentsMessageEndsWithTheFormOfItsDeliverableAndCarriesWhatTheEarlierStepsDelivered() {
        val done = visit(1, "plan", WorkflowNodeKind.AGENT, RunMoves.DONE, "the plan")
        val run = run(visits = listOf(done, visit(2, "t", WorkflowNodeKind.TRIGGER, RunMoves.DONE)))
        val id = sessionOf(RUN, 3)
        fun prompt(deliverable: Deliverable?, instructions: String = INSTRUCTIONS): String {
            val node = AGENT.copy(run = NodeRun(instructions = instructions, deliverable = deliverable))
            return AgentRequests.of(run, node, id).prompt
        }
        val earlier = "Fix main after the plan\n\nWhat the earlier steps delivered:\n- plan: the plan"
        assertEquals(earlier, prompt(null))
        val ending = "When this step is done, call the `deliver` tool of the ostrom-workflow MCP server once, with "
        val forms = listOf(
            Deliverable("artifact") to "the id of the artifact that holds it, as a string",
            Deliverable("url") to "an https address, as a string",
            Deliverable("url", domain = "github.com", label = "the pull request") to
                "an https address on github.com, as a string (the pull request)",
            Deliverable("text") to "the text, as a string",
            Deliverable("json") to "a JSON value, not a string holding JSON",
            Deliverable("json", schema = JsonPrimitive(1)) to "a JSON value that matches the step's schema, not a " +
                "string holding JSON",
        )
        for ((deliverable, what) in forms) {
            assertEquals("$earlier\n\n$ending$what as its `output`, then end your turn.", prompt(deliverable))
        }
        // The instructions give way so that the sentence always fits the first message.
        val long = prompt(Deliverable("text"), "y".repeat(RunTemplates.MAX_TEXT))
        assertEquals(AgentRequests.MAX_PROMPT, long.length)
        assertTrue(long.endsWith("as its `output`, then end your turn."), long.takeLast(60))
        val alone = AGENT.copy(run = NodeRun(instructions = "Fix {{inputs.branch}}"))
        assertEquals("Fix main", AgentRequests.of(run(), alone, id).prompt, "Nothing delivered before: nothing added")
    }

    @Test
    fun anAgentNodeThatAsksForADeliverableFailsWhenItsSessionStopsWithNothingDelivered() = runTest {
        stored(RunGraph("a", listOf(DELIVERING, TRIGGER), listOf(WorkflowEdge("a", "t"))))
        agents.state = null
        played()
        val visit = runs.rows.getValue(RUN).run.visits.single()
        assertEquals("Build", visit.nodeTitle, "A visit names its node's title")
        agents.state = AgentState.WORKING
        assertEquals(RunStatus.RUNNING, played().status, "The agent works: the run waits")

        agents.state = AgentState.DONE
        val ended = played()

        assertEquals(RunStatus.FAILED, ended.status)
        assertEquals("ended_without_delivery", ended.failureCode)
        assertEquals("Node a: failed (${RunMoves.ENDED_WITHOUT_DELIVERY}).", ended.failure)
        assertEquals(listOf("a"), ended.visits.map { it.nodeId }, "The edge of a delivery is not taken")
    }

    @Test
    fun anAgentNodeWhoseSessionFailedFailsTheRunWithItsCodeAndANodeWithoutDeliverableKeepsEndingWithItsSession() =
        runTest {
            stored(RunGraph("a", listOf(DELIVERING)), run(visits = listOf(RUNNING_AGENT)))
            agents.state = AgentState.FAILED
            val failed = played()
            assertEquals(listOf<Any?>(RunStatus.FAILED, "session_failed"), listOf(failed.status, failed.failureCode))

            stored(RunGraph("a", listOf(AGENT), listOf(WorkflowEdge("a", null))), run(visits = listOf(RUNNING_AGENT)))
            agents.state = AgentState.DONE
            val ended = played()
            assertEquals(listOf<Any?>(RunStatus.SUCCEEDED, null), listOf(ended.status, ended.failureCode))
            assertEquals(listOf<String?>("next"), ended.visits.map { it.outcome })
        }

    @Test
    fun aDeliveredVisitTakesItsEdgeOrOneWithoutOnAndTheRunSaysWhatItDeliveredWhole() {
        val moves = RunMoves(OWNER, clock.now)
        val whole = "z".repeat(5_000)
        val delivered = moves.finish(run(visits = listOf(RUNNING_AGENT)), RunMoves.DELIVERED, output = whole).next
        assertEquals(whole, delivered.visits.single().output, "A delivery is kept whole")
        val cut = moves.finish(run(visits = listOf(RUNNING_AGENT)), RunMoves.NEXT, output = whole).next
        assertEquals(500, cut.visits.single().output?.length, "Anything else is cut to a line")

        fun after(vararg edges: WorkflowEdge): WorkflowRun =
            moves.follow(delivered, RunGraph("a", listOf(DELIVERING, TRIGGER), edges.toList())).next
        assertEquals("t", after(WorkflowEdge("a", "t", on = "delivered")).currentNodeId)
        assertEquals("t", after(WorkflowEdge("a", "t")).currentNodeId)
        assertEquals("t", after(WorkflowEdge("a", "t", on = "next")).currentNodeId)
        assertEquals("Node a: delivered (${"z".repeat(500)}).", after(WorkflowEdge("a", "t", on = "x")).failure)
        val ended = after(WorkflowEdge("a", null, on = "delivered"))
        assertEquals(
            listOf<Any?>(RunStatus.SUCCEEDED, whole, null),
            listOf(ended.status, ended.output, ended.failureCode),
        )
        assertEquals(whole, after().output, "A node without an edge ends the run with its delivery")
        // Only an agent visit's own failures have a code.
        val odd = visit(1, "a", WorkflowNodeKind.AGENT, RunMoves.FAILED, "Something else.")
        assertNull(failureCodeOf(odd))
        assertNull(failureCodeOf(odd.copy(output = null)))
        assertNull(failureCodeOf(odd.copy(status = RunMoves.DONE, output = RunMoves.SESSION_FAILED)))
        assertEquals("session_failed", failureCodeOf(odd.copy(output = RunMoves.SESSION_FAILED)))
    }

    @Test
    fun theStarterDeliversWhatTheNodeAsksForOnceAndTheRunMovesOn() = runTest {
        val todos = Todos()
        val deliver = DeliverRunUseCaseImpl(runs, agents, todos, clock)
        val graph = RunGraph("a", listOf(DELIVERING, TRIGGER), listOf(WorkflowEdge("a", "t")))
        stored(graph, run(visits = listOf(RUNNING_AGENT)))
        val output = "https://github.com/acme/api/pull/" + "1".repeat(600)

        val answer = deliver(ADA, RUN, JsonPrimitive(output), PERSON)

        val visit = answer.visits.single()
        assertEquals(
            listOf<Any?>(RunMoves.DONE, "delivered", output),
            listOf(visit.status, visit.outcome, visit.output),
        )
        assertEquals(answer, runs.rows.getValue(RUN).run)
        val change = runs.changes.single()
        assertEquals(listOf<Any?>(null, clock.now), listOf(change.owner, change.dueAt))
        val audit = change.audit.single()
        assertEquals(
            listOf<Any?>("delivered", mapOf("node" to "a", "seq" to "1"), PERSON),
            listOf(audit.event, audit.details, audit.actor),
        )
        assertEquals(listOf(sessionOf(RUN, 1).toString()), todos.asked, "The step's own session's todo is asked")
        // The second delivery of the visit finds it delivered.
        val again = assertFailsWith<NothingToDeliverException> { deliver(ADA, RUN, JsonPrimitive(output), PERSON) }
        assertEquals("nothing_to_deliver", again.type)
        // The engine then follows the delivery's edge.
        assertEquals("t", played().visits.last().nodeId)
    }

    @Test
    fun aDeliveryIsRefusedForAnotherCallerARunThatWaitsForNoneAnOpenTodoOrAWrongOutput() = runTest {
        val todos = Todos()
        val deliver = DeliverRunUseCaseImpl(runs, agents, todos, clock)
        val graph = RunGraph("a", listOf(DELIVERING, AGENT.copy(id = "plain"), TRIGGER))
        val good = JsonPrimitive("https://github.com/acme/api/pull/1")
        suspend fun refusal(run: WorkflowRun, actor: GateActor = ADA, g: RunGraph = graph): Throwable {
            stored(g, run)
            return assertFailsWith<Throwable> { deliver(actor, RUN, good, null) }
        }
        val running = run(visits = listOf(RUNNING_AGENT))
        assertTrue(refusal(running, GateActor("ci", ORG, ActorKind.SERVICE)) is RunNotFoundException)
        assertTrue(refusal(running, GateActor("bob", ORG, ActorKind.USER)) is RunNotFoundException)
        assertTrue(assertFailsWith<Throwable> { deliver(ADA, "wfr_none", good, null) } is RunNotFoundException)
        val nothing = listOf(
            run(visits = emptyList()),
            run(status = RunStatus.CANCELLED, visits = listOf(RUNNING_AGENT)),
            run(visits = listOf(RUNNING_AGENT.copy(status = RunMoves.DONE))),
            run(visits = listOf(visit(1, "t", WorkflowNodeKind.TRIGGER, RunMoves.RUNNING))),
            run(visits = listOf(RUNNING_AGENT.copy(nodeId = "plain"))),
            run(visits = listOf(RUNNING_AGENT.copy(nodeId = "gone"))),
        )
        for (run in nothing) assertTrue(refusal(run) is NothingToDeliverException, run.toString())
        agents.state = null
        assertTrue(refusal(running) is NothingToDeliverException, "The session is not started yet")
        agents.state = AgentState.DONE
        todos.refusal = "2 of 3 items remain."
        val open = refusal(running)
        assertTrue(open is TodoOpenException && open.type == "todo_open" && open.message == "2 of 3 items remain.")
        todos.refusal = null
        stored(graph, running)
        val wrong = assertFailsWith<InvalidDeliverableException> { deliver(ADA, RUN, JsonPrimitive("http://x"), null) }
        assertEquals("Deliver an https address.", wrong.message)
        runs.writes = false
        assertTrue(refusal(running) is NothingToDeliverException, "The run changed meanwhile")
        assertEquals(emptyList(), runs.changes, "Nothing refused is stored")
        // Without a module that keeps todos, a delivery is never refused for one.
        runs.writes = true
        stored(graph, running)
        val untracked = DeliverRunUseCaseImpl(runs, agents, null, clock)(ADA, RUN, good, null)
        assertEquals("delivered", untracked.visits.single().outcome)
    }

    private companion object {
        const val OWNER = "delivery-test"
        const val INSTRUCTIONS = "Fix {{inputs.branch}} after {{nodes.plan.output}}"
        const val AT = "2026-10-30T10:00:00.000Z"
        val RUN = "wfr_" + "d".repeat(32)
        val PERSON: Uuid = Uuid.parse("000000ad-0000-0000-0000-000000000000")
        val ADA = GateActor("ada", ORG, ActorKind.USER, personId = "ada")
        val TRIGGER = WorkflowNode("t", WorkflowNodeKind.TRIGGER, "Push")
        val AGENT = WorkflowNode("a", WorkflowNodeKind.AGENT, "Agent")
        val DELIVERING = WorkflowNode(
            "a",
            WorkflowNodeKind.AGENT,
            "Build",
            run = NodeRun(
                instructions = "Open the pull request",
                deliverable = Deliverable("url", domain = "github.com"),
            ),
        )
        val RUNNING_AGENT = NodeVisit(1, "a", WorkflowNodeKind.AGENT, RunMoves.RUNNING, startedAt = AT)
    }
}
