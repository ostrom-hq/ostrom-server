// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.workflowruns.domain.engine.AgentRequests
import run.ostrom.server.workflowruns.domain.engine.NodePlayers
import run.ostrom.server.workflowruns.domain.engine.PlayRunUseCase
import run.ostrom.server.workflowruns.domain.engine.PlayRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.RunMoves
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCase
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.sessionOf
import run.ostrom.server.workflowruns.domain.models.CancelRunForbiddenException
import run.ostrom.server.workflowruns.domain.models.PlayedRun
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunBusyException
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunEndedException
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.models.RunNotFoundException
import run.ostrom.server.workflowruns.domain.models.RunRef
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.repositories.RunsQuery
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.AgentRequest
import run.ostrom.server.workflowruns.domain.services.AgentState
import run.ostrom.server.workflowruns.domain.services.GateOpening
import run.ostrom.server.workflowruns.domain.services.GateRequest
import run.ostrom.server.workflowruns.domain.services.GateState
import run.ostrom.server.workflowruns.domain.services.RunAgents
import run.ostrom.server.workflowruns.domain.services.RunGates
import run.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.GetWorkflowRunUseCaseImpl
import run.ostrom.server.workflowruns.fakes.FakeChildren
import run.ostrom.server.workflowruns.fakes.FakeWaits
import run.ostrom.server.workflowruns.fakes.FixedClock
import run.ostrom.server.workflowruns.fakes.ORG
import run.ostrom.server.workflowruns.infrastructure.RunEngineWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The engine's moves and loops on fakes: what the database tests do not reach (bounds, failures, races). */
class RunEngineTest {
    private val clock = FixedClock()

    /** The runs in memory: a change is written only on the run as read, like the database's. */
    private class Runs(var writes: Boolean = true) : RunEngineRepository, StartedRunsRepository {
        val rows = mutableMapOf<String, PlayedRun>()
        val released = mutableListOf<Pair<String, Instant?>>()
        val leases = mutableListOf<Instant>()

        override suspend fun claimDue(owner: String, now: Instant, until: Instant, limit: Int): List<RunRef> {
            leases += until
            return rows.keys.map { RunRef(ORG, it) }
        }

        override suspend fun played(orgId: Uuid, id: String): PlayedRun? = rows[id]

        override suspend fun ofSession(orgId: Uuid, sessionId: Uuid): PlayedRun? = null

        override suspend fun write(orgId: Uuid, change: RunChange): Boolean {
            val stored = rows[change.next.id]?.takeIf { writes && it.run == change.expected }
            stored?.let { rows[change.next.id] = it.copy(run = change.next) }
            return stored != null
        }

        override suspend fun release(orgId: Uuid, id: String, owner: String, nextAt: Instant?) {
            released += id to nextAt
        }

        override suspend fun add(orgId: Uuid, run: WorkflowRun, startedAt: Instant, graph: RunGraph, audit: RunAudit) {
            rows[run.id] = PlayedRun(run, graph)
        }

        override suspend fun addChild(orgId: Uuid, child: WorkflowRun, seq: Int, graph: RunGraph, audit: RunAudit) =
            error("The engine's fakes start no child run.")

        override suspend fun find(orgId: Uuid, id: String): WorkflowRun? = rows[id]?.run

        override suspend fun runs(orgId: Uuid, query: RunsQuery): List<WorkflowRun> = rows.values.map { it.run }
    }

    private class Agents(var state: AgentState? = null) : RunAgents {
        val started = mutableListOf<AgentRequest>()
        val stopped = mutableListOf<Uuid>()

        override suspend fun start(orgId: Uuid, request: AgentRequest): String? {
            started += request
            return null
        }

        override suspend fun state(orgId: Uuid, sessionId: Uuid): AgentState? = state

        override suspend fun stop(orgId: Uuid, sessionId: Uuid) {
            stopped += sessionId
        }
    }

    private class Gates(var state: GateState? = GateState.PENDING) : RunGates {
        override suspend fun open(orgId: Uuid, request: GateRequest): GateOpening = GateOpening(GATE)

        override suspend fun state(orgId: Uuid, gateId: Uuid): GateState? = state
    }

    private class Logs : Logger {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            lines += message to fields
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private val runs = Runs()
    private val agents = Agents()
    private val gates = Gates()
    private val play = PlayRunUseCaseImpl(runs, NodePlayers(agents, gates, FakeWaits(), FakeChildren()), clock)

    private fun run(id: String = RUN, status: RunStatus = RunStatus.QUEUED, visits: List<NodeVisit> = emptyList()) =
        WorkflowRun(
            id = id,
            workflowId = "ship",
            workflowName = "Ship",
            workflowVersion = 1,
            status = status,
            startedBy = "ada",
            source = "manual",
            inputs = mapOf("branch" to "main"),
            visits = visits,
            startedAt = "2026-10-30T10:00:00.000Z",
        )

    private fun stored(graph: RunGraph, run: WorkflowRun = run()): WorkflowRun {
        runs.rows[run.id] = PlayedRun(run, graph)
        return run
    }

    private fun visit(seq: Int, node: String, kind: WorkflowNodeKind, status: String, outcome: String? = null) =
        NodeVisit(seq, node, kind, status, outcome = outcome, startedAt = "2026-10-30T10:00:00.000Z")

    private suspend fun played(): WorkflowRun {
        play.play(RunRef(ORG, RUN), OWNER)
        return runs.rows.getValue(RUN).run
    }

    @Test
    fun aRunThatLoopsWithoutWaitingIsLeftAfterABoundedNumberOfChanges() = runTest {
        val graph = RunGraph("t", listOf(TRIGGER.copy(maxVisits = 50)), listOf(WorkflowEdge("t", "t")))
        stored(graph)

        val next = play.play(RunRef(ORG, RUN), OWNER)

        assertEquals(clock.now, next)
        assertEquals(PlayRunUseCaseImpl.MAX_MOVES / 2, runs.rows.getValue(RUN).run.visits.size)
    }

    @Test
    fun aRunGoneOrChangedMeanwhileIsLeftAsItIs() = runTest {
        assertNull(play.play(RunRef(ORG, "wfr_gone"), OWNER))
        stored(RunGraph("t", listOf(TRIGGER)))
        runs.writes = false

        assertEquals(clock.now, play.play(RunRef(ORG, RUN), OWNER))
        assertEquals(RunStatus.QUEUED, runs.rows.getValue(RUN).run.status)
    }

    @Test
    fun aGateGoneFailsItsVisitAndAWaitingGateAsksForALaterLook() = runTest {
        val gate = visit(1, "g", WorkflowNodeKind.GATE, RunMoves.WAITING).copy(gateId = GATE.toString())
        stored(RunGraph("g", listOf(GATE_NODE)), run(status = RunStatus.WAITING, visits = listOf(gate)))

        assertEquals(clock.now + PlayRunUseCaseImpl.POLL, play.play(RunRef(ORG, RUN), OWNER))
        gates.state = null
        val ended = played()

        assertEquals(RunStatus.FAILED, ended.status)
        assertEquals("Node g: failed (The gate is gone.).", ended.failure)
    }

    @Test
    fun aCancelledRunEndsOnlyTheSessionOfAnAgentVisit() = runTest {
        val agent = visit(1, "a", WorkflowNodeKind.AGENT, RunMoves.FAILED, "cancelled")
        val gate = visit(1, "g", WorkflowNodeKind.GATE, RunMoves.FAILED, "cancelled")
        // A node kind the server does not play started nothing; a trigger and a check start nothing.
        val check = visit(1, "c", WorkflowNodeKind.CHECK, RunMoves.FAILED, "cancelled")
        val step = visit(1, "s", WorkflowNodeKind.STEP, RunMoves.FAILED, "cancelled")
        val trigger = visit(1, "t", WorkflowNodeKind.TRIGGER, RunMoves.FAILED, "cancelled")
        listOf(
            run("wfr_${"1".repeat(32)}", RunStatus.CANCELLED, listOf(agent)),
            run("wfr_${"2".repeat(32)}", RunStatus.CANCELLED, listOf(gate)),
            run("wfr_${"3".repeat(32)}", RunStatus.CANCELLED),
            run("wfr_${"4".repeat(32)}", RunStatus.SUCCEEDED, listOf(agent)),
            run("wfr_${"5".repeat(32)}", RunStatus.CANCELLED, listOf(check)),
            run("wfr_${"6".repeat(32)}", RunStatus.CANCELLED, listOf(trigger)),
            run("wfr_${"7".repeat(32)}", RunStatus.CANCELLED, listOf(step)),
        ).forEach { stored(RunGraph(), it) }

        val next = runs.rows.keys.map { play.play(RunRef(ORG, it), OWNER) }

        assertEquals(List(7) { null }, next)
        assertEquals(listOf(sessionOf("wfr_${"1".repeat(32)}", 1)), agents.stopped)
    }

    @Test
    fun anAgentNodeAsksItsSessionOnceThenWaitsForIt() = runTest {
        stored(RunGraph("a", listOf(AGENT_NODE)))

        assertEquals(clock.now + PlayRunUseCaseImpl.POLL, play.play(RunRef(ORG, RUN), OWNER))
        agents.state = AgentState.WORKING
        assertEquals(clock.now + PlayRunUseCaseImpl.POLL, play.play(RunRef(ORG, RUN), OWNER))

        assertEquals(1, agents.started.size)
        assertEquals(sessionOf(RUN, 1), agents.started.single().sessionId)
    }

    @Test
    fun theSessionOfAVisitIsTheRunsIdChangedByItsSequence() {
        val first = sessionOf(RUN, 1)
        assertEquals(first, sessionOf(RUN, 1))
        assertEquals(36, first.toString().length)
        assertEquals(setOf(first, sessionOf(RUN, 2), sessionOf(RUN, 3)).size, 3)
    }

    @Test
    fun anAgentsPromptIsItsInstructionsElseItsDetailElseItsLabelWithTheInputs() {
        val run = run()
        val id = sessionOf(RUN, 1)
        val spec = AgentRequests.of(
            run,
            AGENT_NODE.copy(run = NodeRun(instructions = "Fix {{inputs.branch}}", agent = "codex", model = "gpt")),
            id,
        )
        assertEquals(AgentRequest(id, "ada", "Ship · Agent", "codex", "gpt", "Fix main"), spec)
        val detail = AgentRequests.of(run, AGENT_NODE.copy(run = NodeRun(), detail = "Ship {{inputs.branch}}"), id)
        assertEquals(listOf("claude", null, "Ship main"), listOf(detail.agent, detail.model, detail.prompt))
        val label = AgentRequests.of(run.copy(workflowName = "W".repeat(200)), AGENT_NODE, id)
        assertEquals("Agent", label.prompt)
        assertEquals(AgentRequests.MAX_NAME, label.name.length)
    }

    @Test
    fun theMovesBoundTheVisitsAndFollowTheEdgeTheOutcomeTakes() {
        val moves = RunMoves(OWNER, clock.now)
        val loop = RunGraph("t", listOf(TRIGGER.copy(maxVisits = 500)), listOf(WorkflowEdge("t", "t")))
        val full = run(visits = List(RunMoves.MAX_VISITS) { visit(it + 1, "t", WorkflowNodeKind.TRIGGER, "done") })
        assertEquals("The run made 100 visits, the most.", moves.begin(full, loop, "t").next.failure)
        // A graph without a start begins at its first node.
        assertEquals("t", moves.start(run(), RunGraph(null, listOf(TRIGGER))).next.currentNodeId)

        fun after(outcome: String, vararg edges: WorkflowEdge): WorkflowRun {
            val done = run(visits = listOf(visit(1, "a", WorkflowNodeKind.AGENT, "done", outcome)))
            val graph = RunGraph("a", listOf(AGENT_NODE, TRIGGER), edges.toList())
            return moves.follow(done, graph).next
        }
        assertEquals(RunStatus.SUCCEEDED, after("next").status)
        assertEquals("Node a: failed.", after("failed").failure)
        assertEquals("Node a: failed.", after("failed", WorkflowEdge("a", "t")).failure)
        assertEquals("Node a: odd.", after("odd", WorkflowEdge("a", "t", on = "next")).failure)
        // A yes whose only edge takes another outcome has nowhere to go.
        assertEquals("Node a: approved.", after("approved", WorkflowEdge("a", "t", on = "rejected")).failure)
        assertEquals("t", after("next", WorkflowEdge("a", "t", on = "next")).currentNodeId)
        assertEquals("t", after("approved", WorkflowEdge("a", "t")).currentNodeId)
        val chosen = after("failed", WorkflowEdge("a", null, on = "x"), WorkflowEdge("a", "t", on = "failed"))
        assertEquals("t", chosen.currentNodeId)
        assertEquals(RunStatus.SUCCEEDED, after("next", WorkflowEdge("a", null)).status)
        // A trigger after an agent keeps the run's session: only an agent node changes it.
        val kept = moves.begin(run().copy(sessionId = "s"), RunGraph("t", listOf(TRIGGER)), "t").next
        assertEquals("s", kept.sessionId)
    }

    @Test
    fun aPassReleasesEachRunWithItsNextLookAndRetriesOneThatFailedToPlay() = runTest {
        val logs = Logs()
        stored(RunGraph("t", listOf(TRIGGER)))
        stored(RunGraph("t", listOf(TRIGGER)), run("wfr_${"b".repeat(32)}"))
        val failing = object : PlayRunUseCase {
            override suspend fun play(ref: RunRef, owner: String): Instant? =
                if (ref.id == RUN) error("database") else null
        }
        val tick = TickRunsUseCaseImpl(runs, failing, clock, logs, OWNER)

        assertEquals(2, tick.tick())

        val other = "wfr_${"b".repeat(32)}"
        assertEquals(listOf(RUN to clock.now + TickRunsUseCaseImpl.RETRY, other to null), runs.released)
        assertEquals(listOf(clock.now + TickRunsUseCaseImpl.LEASE), runs.leases)
        assertEquals(listOf<Any>("workflow run step failed" to mapOf("error" to "IllegalStateException")), logs.lines)
        val cancelled = TickRunsUseCaseImpl(
            runs,
            object : PlayRunUseCase {
                override suspend fun play(ref: RunRef, owner: String): Instant? = throw CancellationException("stop")
            },
            clock,
            logs,
        )
        assertFailsWith<CancellationException> { cancelled.tick() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theWorkerPassesAgainAtOnceWhileRunsAreDueAndLogsAFailedPass() = runTest {
        val logs = Logs()
        var passes = 0
        val ticks = object : TickRunsUseCase {
            override suspend fun tick(): Int {
                passes++
                return when (passes) {
                    1 -> 3
                    2 -> error("database")
                    else -> 0
                }
            }
        }
        val worker = RunEngineWorker(ticks, logs, 2.seconds)

        val job = launch { worker.run() }
        advanceTimeBy(3.seconds)
        job.cancel()

        assertEquals(3, passes)
        val failed = "workflow runs engine pass failed" to mapOf("error" to "IllegalStateException")
        assertEquals(listOf<Any>(failed), logs.lines)
        val stopped = RunEngineWorker(
            object : TickRunsUseCase {
                override suspend fun tick(): Int = throw CancellationException("stop")
            },
            logs,
        )
        assertFailsWith<CancellationException> { stopped.pass() }
    }

    @Test
    fun onlyAnOwnerOrAnAdminCancelsARunStillUnderWay() = runTest {
        val cancel = CancelWorkflowRunUseCaseImpl(runs, runs, clock)
        val gate = visit(1, "g", WorkflowNodeKind.GATE, RunMoves.WAITING)
        val done = visit(1, "t", WorkflowNodeKind.TRIGGER, RunMoves.DONE, "next")
        stored(RunGraph(), run(status = RunStatus.WAITING, visits = listOf(done, gate.copy(seq = 2))))
        val ended = "wfr_${"e".repeat(32)}"
        stored(RunGraph(), run(ended, RunStatus.SUCCEEDED))

        for (actor in listOf(person(OrgRole.MEMBER), GateActor("ci", ORG, ActorKind.SERVICE))) {
            assertFailsWith<CancelRunForbiddenException> { cancel(actor, RUN, null) }
        }
        assertFailsWith<RunNotFoundException> { cancel(person(OrgRole.ADMIN), "wfr_none", null) }
        assertFailsWith<RunEndedException> { cancel(person(OrgRole.OWNER), ended, null) }
        runs.writes = false
        assertFailsWith<RunBusyException> { cancel(person(OrgRole.OWNER), RUN, null) }
        runs.writes = true

        val cancelled = cancel(person(OrgRole.ADMIN), RUN, null)

        assertEquals(RunStatus.CANCELLED, cancelled.status)
        val closed = gate.copy(seq = 2, status = RunMoves.FAILED, outcome = "cancelled", endedAt = cancelled.endedAt)
        assertEquals(listOf(done, closed), cancelled.visits)
        assertFailsWith<RunNotFoundException> { GetWorkflowRunUseCaseImpl(runs)(person(OrgRole.MEMBER), "wfr_none") }
        assertEquals(cancelled, GetWorkflowRunUseCaseImpl(runs)(person(OrgRole.MEMBER), RUN))
    }

    private fun person(role: OrgRole) = GateActor.person("ada", ORG, role)

    private companion object {
        const val OWNER = "engine-test"
        val RUN = "wfr_" + "a".repeat(32)
        val GATE: Uuid = Uuid.parse("0000000f-0000-0000-0000-000000000001")
        val TRIGGER = WorkflowNode("t", WorkflowNodeKind.TRIGGER, "Push")
        val AGENT_NODE = WorkflowNode("a", WorkflowNodeKind.AGENT, "Agent")
        val GATE_NODE = WorkflowNode("g", WorkflowNodeKind.GATE, "Review", approvers = listOf("ada"))
    }
}
