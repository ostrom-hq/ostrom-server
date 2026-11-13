// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.NodeCondition
import run.ostrom.core.api.models.NodeConditionOp
import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WaitAction
import run.ostrom.core.api.models.WaitSpec
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowInput
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.workflowruns.domain.engine.NodePlayers
import run.ostrom.server.workflowruns.domain.engine.PlayRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.RunMoves
import run.ostrom.server.workflowruns.domain.engine.StartedRunChildren
import run.ostrom.server.workflowruns.domain.models.PlayedRun
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.models.RunRef
import run.ostrom.server.workflowruns.domain.models.RunWorkflow
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.repositories.RunsQuery
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.AgentRequest
import run.ostrom.server.workflowruns.domain.services.AgentState
import run.ostrom.server.workflowruns.domain.services.ChildRequest
import run.ostrom.server.workflowruns.domain.services.ChildResult
import run.ostrom.server.workflowruns.domain.services.GateOpening
import run.ostrom.server.workflowruns.domain.services.GateRequest
import run.ostrom.server.workflowruns.domain.services.GateState
import run.ostrom.server.workflowruns.domain.services.RunAgents
import run.ostrom.server.workflowruns.domain.services.RunGates
import run.ostrom.server.workflowruns.domain.services.RunWorkflows
import run.ostrom.server.workflowruns.domain.services.WaitRequest
import run.ostrom.server.workflowruns.domain.services.WaitResult
import run.ostrom.server.workflowruns.domain.services.WaitState
import run.ostrom.server.workflowruns.fakes.CHILD
import run.ostrom.server.workflowruns.fakes.FakeChildren
import run.ostrom.server.workflowruns.fakes.FakeWaits
import run.ostrom.server.workflowruns.fakes.FixedClock
import run.ostrom.server.workflowruns.fakes.ORG
import run.ostrom.server.workflowruns.fakes.WAIT
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The `wait` and `workflow` nodes on fakes, and the child runs on runs in memory. */
class RunNodesTest {
    private val clock = FixedClock()

    /** The runs in memory: a change is written only on the run as read; one child per parent's visit. */
    private class Runs(var writes: Boolean = true) : RunEngineRepository, StartedRunsRepository {
        val rows = mutableMapOf<String, PlayedRun>()
        val audits = mutableListOf<RunAudit>()
        private val children = mutableMapOf<Pair<String?, Int>, String>()

        override suspend fun claimDue(owner: String, now: Instant, until: Instant, limit: Int): List<RunRef> =
            rows.keys.map { RunRef(ORG, it) }

        override suspend fun played(orgId: Uuid, id: String): PlayedRun? = rows[id]

        override suspend fun write(orgId: Uuid, change: RunChange): Boolean {
            val stored = rows[change.next.id]?.takeIf { writes && it.run == change.expected }
            stored?.let {
                rows[change.next.id] = it.copy(run = change.next)
                audits += change.audit
            }
            return stored != null
        }

        override suspend fun release(orgId: Uuid, id: String, owner: String, nextAt: Instant?) = Unit

        override suspend fun add(orgId: Uuid, run: WorkflowRun, startedAt: Instant, graph: RunGraph, audit: RunAudit) {
            rows[run.id] = PlayedRun(run, graph)
        }

        override suspend fun addChild(orgId: Uuid, child: WorkflowRun, seq: Int, graph: RunGraph, audit: RunAudit) =
            children.getOrPut(child.parentRunId to seq) {
                rows[child.id] = PlayedRun(child, graph)
                audits += audit
                child.id
            }

        override suspend fun find(orgId: Uuid, id: String): WorkflowRun? = rows[id]?.run

        override suspend fun runs(orgId: Uuid, query: RunsQuery): List<WorkflowRun> = rows.values.map { it.run }
    }

    private object NoAgents : RunAgents {
        override suspend fun start(orgId: Uuid, request: AgentRequest): String = error("No agent here.")

        override suspend fun state(orgId: Uuid, sessionId: Uuid): AgentState = error("No agent here.")

        override suspend fun stop(orgId: Uuid, sessionId: Uuid) = Unit
    }

    private object NoGates : RunGates {
        override suspend fun open(orgId: Uuid, request: GateRequest): GateOpening = error("No gate here.")

        override suspend fun state(orgId: Uuid, gateId: Uuid): GateState = error("No gate here.")
    }

    private class Workflows(vararg known: RunWorkflow) : RunWorkflows {
        private val byId = known.associateBy { it.id }

        override suspend fun find(orgId: Uuid, id: String): RunWorkflow? = byId[id]
    }

    private val runs = Runs()
    private val waits = FakeWaits()
    private val children = FakeChildren()
    private val play = PlayRunUseCaseImpl(runs, NodePlayers(NoAgents, NoGates, waits, children), clock)

    private fun run(
        id: String = RUN,
        status: RunStatus = RunStatus.QUEUED,
        visits: List<NodeVisit> = emptyList(),
        workflowId: String = "ship",
        parentRunId: String? = null,
    ) = WorkflowRun(
        id = id,
        workflowId = workflowId,
        workflowName = "Ship",
        workflowVersion = 1,
        status = status,
        startedBy = "ada",
        source = "manual",
        inputs = mapOf("branch" to "main"),
        parentRunId = parentRunId,
        visits = visits,
        startedAt = "2026-10-30T10:00:00.000Z",
    )

    private fun stored(graph: RunGraph, run: WorkflowRun = run()): WorkflowRun {
        runs.rows[run.id] = PlayedRun(run, graph)
        return run
    }

    private suspend fun played(): WorkflowRun {
        play.play(RunRef(ORG, RUN), OWNER)
        return runs.rows.getValue(RUN).run
    }

    @Test
    fun aWaitNodeAsksItsFirstApproverOnceThenFollowsTheActionItsPersonChose() = runTest {
        val edges = listOf(WorkflowEdge("w", "t", on = "ship"), WorkflowEdge("w", null, on = "hold"))
        stored(RunGraph("w", listOf(WAIT_NODE, TRIGGER), edges))

        assertEquals(clock.now + PlayRunUseCaseImpl.POLL, play.play(RunRef(ORG, RUN), OWNER))

        val waiting = runs.rows.getValue(RUN).run
        assertEquals(RunStatus.WAITING, waiting.status)
        val visit = waiting.visits.single()
        assertEquals(listOf<Any?>(RunMoves.WAITING, WAIT.toString()), listOf(visit.status, visit.waitId))
        assertEquals(listOf(WaitRequest(RUN, 1, "ada", "bea", QUESTION)), waits.asked)
        // Nobody answered: the run waits, without asking again.
        assertEquals(waiting, played())
        assertEquals(1, waits.asked.size)

        waits.result = WaitResult(WaitState.ANSWERED, "ship", """{"note":"ok"}""")
        val ended = played()

        assertEquals(RunStatus.SUCCEEDED, ended.status)
        assertEquals(listOf("ship", "next"), ended.visits.map { it.outcome })
        assertEquals("""{"note":"ok"}""", ended.visits.first().output)
        val asked = runs.audits.first { it.event == "wait_asked" }
        assertEquals(mapOf("node" to "w", "seq" to "1", "wait" to WAIT.toString()), asked.details)
    }

    @Test
    fun aWaitIsAskedOfItsOwnerElseTheStarterAndAnAnswerWithoutItsOwnEdgeTakesTheEdgeWithout() = runTest {
        stored(RunGraph("w", listOf(WAIT_NODE.copy(approvers = null, owner = "cy"), TRIGGER), listOf(TO_TRIGGER)))
        waits.result = WaitResult(WaitState.ANSWERED, "done")
        assertEquals(RunStatus.SUCCEEDED, played().status)

        stored(RunGraph("w", listOf(WAIT_NODE.copy(approvers = emptyList()))), run())
        val ended = played()

        assertEquals(listOf("cy", null), waits.asked.map { it.assignee })
        // No edge at all: an answer ends the run, as any node that did its part.
        assertEquals(RunStatus.SUCCEEDED, ended.status)
        stored(RunGraph("w", listOf(WAIT_NODE, TRIGGER), listOf(WorkflowEdge("w", "t", on = "ship"))), run())
        assertEquals("Node w: done.", played().failure)
    }

    @Test
    fun aWaitWithoutQuestionRefusedCancelledOrGoneFailsItsVisitWhateverItsEdges() = runTest {
        suspend fun failure(node: WorkflowNode = WAIT_NODE): String? {
            stored(RunGraph("w", listOf(node, TRIGGER), listOf(TO_TRIGGER)), run())
            return played().failure
        }
        assertEquals("Node w: failed (The wait node asks no question.).", failure(WAIT_NODE.copy(wait = null)))
        waits.refusal = "title is 1 to 200 characters, without control characters."
        assertEquals("Node w: failed (title is 1 to 200 characters, without control characters.).", failure())
        waits.refusal = null
        waits.result = WaitResult(WaitState.CANCELLED)
        assertEquals("Node w: failed (The wait was cancelled.).", failure())
        waits.result = null
        assertEquals("Node w: failed (The wait is gone.).", failure())
    }

    @Test
    fun aCheckNodeFollowsTheBranchItsConditionChoosesAndFailsTheRunWhenItDoesNotEvaluate() = runTest {
        val condition = NodeCondition("w", "output.note", NodeConditionOp.EQUALS, "go")
        val check = WorkflowNode("c", WorkflowNodeKind.CHECK, "Go?", condition = condition)
        val edges = listOf(
            WorkflowEdge("w", "c", on = "ship"),
            WorkflowEdge("c", "t", on = "true"),
            WorkflowEdge("c", null, on = "false"),
        )
        suspend fun ended(note: String, node: WorkflowNode = check): WorkflowRun {
            stored(RunGraph("w", listOf(WAIT_NODE, node, TRIGGER), edges), run())
            waits.result = WaitResult(WaitState.ANSWERED, "ship", """{"note":"$note"}""")
            play.play(RunRef(ORG, RUN), OWNER)
            return played()
        }

        val yes = ended("go")
        val no = ended("stop")

        assertEquals(listOf("ship", "true", "next"), yes.visits.map { it.outcome })
        assertEquals(listOf(RunStatus.SUCCEEDED, RunStatus.SUCCEEDED), listOf(yes.status, no.status))
        assertEquals(listOf("ship", "false"), no.visits.map { it.outcome })
        // A value the step does not have, or no condition at all: the run fails, never a branch by default.
        val missing = check.copy(condition = condition.copy(path = "output.missing"))
        assertEquals(
            "Node c: failed (The condition reads output.missing of step w, which has no value.).",
            ended("go", missing).failure,
        )
        assertEquals(
            "Node c: failed (The check node has no condition: the server plays a check node by its condition only.).",
            ended("go", check.copy(condition = null)).failure,
        )
    }

    @Test
    fun aWorkflowNodeStartsItsChildOnceThenEndsAsItsChildEnds() = runTest {
        val node = CHILD_NODE.copy(arguments = mapOf("branch" to "{{inputs.branch}}"))
        stored(RunGraph("c", listOf(node)))
        children.result = ChildResult(RunStatus.QUEUED)

        assertEquals(clock.now + PlayRunUseCaseImpl.POLL, play.play(RunRef(ORG, RUN), OWNER))

        val running = runs.rows.getValue(RUN).run
        assertEquals(RunStatus.RUNNING, running.status)
        val visit = running.visits.single()
        assertEquals(listOf<Any?>(RunMoves.RUNNING, CHILD), listOf(visit.status, visit.childRunId))
        val request = children.started.single()
        assertEquals(
            listOf<Any>(RUN, 1, "build", mapOf("branch" to "{{inputs.branch}}")),
            listOf(request.parent.id, request.seq, request.workflowId, request.arguments),
        )
        children.result = ChildResult(RunStatus.WAITING)
        assertEquals(running, played())

        children.result = ChildResult(RunStatus.SUCCEEDED, output = "built")
        val ended = played()

        assertEquals(RunStatus.SUCCEEDED, ended.status)
        assertEquals(listOf("next" to "built"), ended.visits.map { it.outcome to it.output })
        assertEquals("built", ended.output, "The run says what its last node handed on")
        assertEquals(1, children.started.size)
        val started = runs.audits.first { it.event == "child_started" }
        assertEquals(mapOf("node" to "c", "seq" to "1", "child" to CHILD), started.details)
    }

    @Test
    fun aWorkflowNodeFailsWithoutWorkflowRefusedOrWhenItsChildFailsIsCancelledOrIsGone() = runTest {
        suspend fun failure(node: WorkflowNode = CHILD_NODE): String? {
            stored(RunGraph("c", listOf(node)), run())
            play.play(RunRef(ORG, RUN), OWNER)
            return played().failure
        }
        val nameless = failure(CHILD_NODE.copy(workflowId = null))
        assertEquals("Node c: failed (The workflow node names no workflow.).", nameless)
        children.refusal = "Workflow build is not in the organization."
        assertEquals("Node c: failed (Workflow build is not in the organization.).", failure())
        children.refusal = null
        children.result = ChildResult(RunStatus.FAILED, failure = "Node a: failed.")
        assertEquals("Node c: failed (The child run failed: Node a: failed.).", failure())
        children.result = ChildResult(RunStatus.CANCELLED)
        assertEquals("Node c: failed (The child run was cancelled.).", failure())
        children.result = null
        assertEquals("Node c: failed (The child run is gone.).", failure())
    }

    @Test
    fun aCancelledRunWithdrawsItsWaitAndCancelsItsChildRun() = runTest {
        val wait = NodeVisit(1, "w", WorkflowNodeKind.WAIT, RunMoves.FAILED, "cancelled", waitId = WAIT.toString(),
            startedAt = AT)
        val child = NodeVisit(1, "c", WorkflowNodeKind.WORKFLOW, RunMoves.FAILED, "cancelled", childRunId = CHILD,
            startedAt = AT)
        listOf(wait, wait.copy(waitId = null), child, child.copy(childRunId = null)).forEachIndexed { i, visit ->
            stored(RunGraph(), run("wfr_${"$i".repeat(32)}", RunStatus.CANCELLED, listOf(visit)))
        }

        runs.rows.keys.forEach { assertNull(play.play(RunRef(ORG, it), OWNER)) }

        assertEquals(listOf(WAIT), waits.cancelled)
        assertEquals(listOf(CHILD), children.cancelled)
    }

    @Test
    fun aChildRunIsAQueuedRunOfTheParentsStarterWithTheArgumentsAsInputsStartedOncePerVisit() = runTest {
        val starts = StartedRunChildren(Workflows(BUILD), runs, runs, clock)
        val parent = stored(RunGraph(), run(status = RunStatus.RUNNING))
        val request = ChildRequest(parent, 2, "build", mapOf("target" to "{{inputs.branch}}-x"))

        val opened = starts.start(ORG, request)

        val id = opened.runId.orEmpty()
        val child = runs.rows.getValue(id)
        assertEquals(BUILD.graph, child.graph)
        assertEquals(
            listOf<Any?>("build", "Build", 3, RunStatus.QUEUED, "ada", "workflow", mapOf("target" to "main-x"), RUN),
            child.run.let {
                listOf(
                    it.workflowId, it.workflowName, it.workflowVersion, it.status, it.startedBy, it.source, it.inputs,
                    it.parentRunId,
                )
            },
        )
        assertEquals(
            mapOf("workflow" to "build", "version" to "3", "parent" to RUN),
            runs.audits.single().details,
        )
        // The same visit started again (after a restart) finds its child.
        assertEquals(opened, starts.start(ORG, request))
        assertEquals(1, runs.audits.size)
        assertEquals(ChildResult(RunStatus.QUEUED), starts.result(ORG, id))
        assertNull(starts.result(ORG, "wfr_none"))
    }

    @Test
    fun aChildRunIsRefusedForACycleBeyondTheDepthOutsideTheOrganizationForAProposalOrBadInputs() = runTest {
        val starts = StartedRunChildren(
            Workflows(BUILD, BUILD.copy(id = "draft", status = WorkflowStatus.PROPOSED)),
            runs,
            runs,
            clock,
        )
        suspend fun refusal(workflowId: String, parent: WorkflowRun = run(), arguments: Map<String, String> = TARGET) =
            starts.start(ORG, ChildRequest(parent, 1, workflowId, arguments)).refusal

        // A chain of runs one deeper than the most: each one the child of the one before.
        val chain = List(StartedRunChildren.MAX_DEPTH + 1) { depth ->
            val parent = if (depth == 0) null else "wfr_${"${depth - 1}".repeat(32)}"
            run("wfr_${"$depth".repeat(32)}", workflowId = "w$depth", parentRunId = parent)
        }
        chain.forEach { stored(RunGraph(), it) }

        assertEquals(
            "Workflow w0 already runs above this node: a workflow never calls itself.",
            refusal("w0", chain[2]),
        )
        assertEquals("A run nests at most 5 runs deep.", refusal("build", chain.last()))
        assertNull(refusal("build", chain[3]))
        assertEquals("Workflow gone is not in the organization.", refusal("gone"))
        assertEquals("Workflow draft is still a proposal.", refusal("draft"))
        assertEquals("inputs.target: required.", refusal("build", arguments = emptyMap()))
    }

    @Test
    fun aChildRunIsCancelledUnlessGoneEndedOrChangedEachTimeItIsRead() = runTest {
        val starts = StartedRunChildren(Workflows(), runs, runs, clock)
        val visit = NodeVisit(1, "t", WorkflowNodeKind.TRIGGER, RunMoves.RUNNING, startedAt = AT)
        val child = stored(RunGraph(), run(CHILD, RunStatus.RUNNING, listOf(visit), parentRunId = RUN))
        val ended = stored(RunGraph(), run("wfr_${"e".repeat(32)}", RunStatus.SUCCEEDED))

        starts.cancel(ORG, "wfr_none")
        starts.cancel(ORG, ended.id)
        runs.writes = false
        starts.cancel(ORG, CHILD)
        assertEquals(child, runs.rows.getValue(CHILD).run)
        runs.writes = true
        starts.cancel(ORG, CHILD)

        val cancelled = runs.rows.getValue(CHILD).run
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertEquals(listOf("failed" to "cancelled"), cancelled.visits.map { it.status to it.outcome })
        assertEquals(RunStatus.SUCCEEDED, runs.rows.getValue(ended.id).run.status)
        assertEquals(mapOf("by" to "parent", "parent" to RUN), runs.audits.single().details)
    }

    private companion object {
        const val OWNER = "engine-test"
        const val AT = "2026-10-30T10:00:00.000Z"
        val TARGET = mapOf("target" to "x")
        val RUN = "wfr_" + "a".repeat(32)
        val QUESTION = WaitSpec("Ship it?", actions = listOf(WaitAction("ship", "Ship"), WaitAction("hold", "Hold")))
        val TRIGGER = WorkflowNode("t", WorkflowNodeKind.TRIGGER, "Done")
        val TO_TRIGGER = WorkflowEdge("w", "t")
        val WAIT_NODE =
            WorkflowNode("w", WorkflowNodeKind.WAIT, "Ask", approvers = listOf("bea", "cy"), wait = QUESTION)
        val CHILD_NODE = WorkflowNode("c", WorkflowNodeKind.WORKFLOW, "Build", workflowId = "build")
        val BUILD = RunWorkflow(
            id = "build",
            name = "Build",
            version = 3,
            status = WorkflowStatus.ACCEPTED,
            inputs = listOf(WorkflowInput("target", "Target", required = true)),
            graph = RunGraph("t", listOf(TRIGGER)),
        )
    }
}
