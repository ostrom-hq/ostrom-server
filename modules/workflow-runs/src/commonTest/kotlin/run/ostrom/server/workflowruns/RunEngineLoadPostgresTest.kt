// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WaitAction
import run.ostrom.core.api.models.WaitSpec
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.automations.AutomationsModule
import run.ostrom.server.automations.domain.AnswerWaitUseCase
import run.ostrom.server.automations.domain.WaitCaller
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.launches.LaunchesModule
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.runners.RunnersModule
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.CloudPlanProvider
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.benchTest
import run.ostrom.server.workflowruns.domain.engine.PlayRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCase
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.sessionOf
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.fakes.FixedClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * The engine under load, on PostgreSQL with the real modules: 100 runs of an `agent` → `gate` workflow, in 10
 * organizations, played at once by 4 replicas that lease them through `workflow_runs_claim_due`, while their sessions
 * end and their gates are answered all together. Every run ends `succeeded`, each visit is played once (its key
 * inserted once), each agent node starts one cloud session, and PostgreSQL counts no deadlock. The figures (wall time,
 * pass p50 and p95 of each phase) are written to `build/reports/perf/run-engine-concurrency.txt`. The same for 100
 * runs of a `wait` → `workflow` workflow, whose waits are answered all together and whose child runs are played by the
 * same replicas: `build/reports/perf/run-nodes-concurrency.txt`.
 */
class RunEngineLoadPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        RunnersModule, LaunchesModule, AutomationsModule, WorkflowRunsModule,
    )

    // No running cap: 10 sessions at once in an organization would pass the free plan's 5.
    private val overrides = module {
        single<CloudPlanProvider> {
            object : CloudPlanProvider {
                override suspend fun planFor(orgId: Uuid) =
                    CloudPlan(CloudPlan.UNLIMITED_SESSIONS, null, 30, 240, true, true)
            }
        }
    }

    private val clock = FixedClock(Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + 1_000))

    /** An organization's owner, who starts its runs, and the member who approves their gates and answers waits. */
    private class Owner(val login: String, val org: Org, val approver: Uuid)

    /** The passes of the replicas, by phase. */
    private class Passes {
        private val lock = Mutex()
        val times = linkedMapOf<String, MutableList<Duration>>()

        suspend fun add(phase: String, took: Duration) =
            lock.withLock { times.getOrPut(phase) { mutableListOf() } += took }
    }

    @Test
    fun aHundredRunsPlayedAtOnceByFourReplicasEachEndOnceWithoutDeadlock() = benchTest(modules, overrides) { server ->
        val owners = List(ORGS) { server.owner("load-$it") }
        val runs = owners.flatMap { owner -> List(RUNS_EACH) { owner to server.queued(owner) } }
        val replicas = List(REPLICAS) { server.engine("engine-load-$it") }
        val passes = Passes()
        val deadlocksBefore = server.deadlocks()
        val started = TimeSource.Monotonic.markNow()

        replicas.round("agent nodes start their sessions", passes)
        coroutineScope {
            runs.map { (owner, run) -> async { server.endSession(owner.org.id, sessionOf(run.id, 1)) } }.awaitAll()
        }
        replicas.round("sessions ended, gates opened", passes)
        coroutineScope { runs.map { (owner, run) -> async { server.approve(owner, run) } }.awaitAll() }
        replicas.round("gates approved, runs ended", passes)

        val wall = started.elapsedNow()
        val ended = runs.map { (owner, run) -> server.stored(owner.org.id, run.id) }
        val orgs = owners.map { it.org.id }
        val visitKeys = server.count("workflow_runs_visits", orgs)
        val sessions = server.count("runners_sessions", orgs)
        val deadlocks = server.deadlocks() - deadlocksBefore
        val report = buildString {
            appendLine("$ORGS organizations x $RUNS_EACH runs (agent -> gate), $REPLICAS replicas; in-process, pool 4")
            appendLine("${ended.count { it.status == RunStatus.SUCCEEDED }} of ${runs.size} runs succeeded in $wall")
            passes.times.forEach { (phase, times) -> appendLine(line(phase, times)) }
            appendLine("visit keys: $visitKeys (expected ${runs.size * 2}); cloud sessions: $sessions")
            appendLine("deadlocks: $deadlocks")
        }
        write("run-engine-concurrency", report)
        assertEquals(List(runs.size) { RunStatus.SUCCEEDED }, ended.map { it.status }, report)
        assertEquals(List(runs.size) { listOf("next", "approved") }, ended.map { r -> r.visits.map { it.outcome } })
        assertEquals(runs.size * 2L, visitKeys, report)
        assertEquals(runs.size.toLong(), sessions, report)
        assertEquals(0L, deadlocks, report)
    }

    @Test
    fun aHundredRunsThroughWaitsAndChildRunsEndOnceWithoutDeadlock() = benchTest(modules, overrides) { server ->
        val owners = List(ORGS) { server.owner("nodes-$it") }
        owners.forEach { server.childWorkflow(it) }
        val runs = owners.flatMap { owner -> List(RUNS_EACH) { owner to server.queued(owner, NODES) } }
        val replicas = List(REPLICAS) { server.engine("engine-nodes-$it") }
        val passes = Passes()
        val deadlocksBefore = server.deadlocks()
        val started = TimeSource.Monotonic.markNow()

        replicas.round("wait nodes ask their questions", passes)
        coroutineScope { runs.map { (owner, run) -> async { server.answer(owner, run) } }.awaitAll() }
        replicas.round("waits answered, child runs started and played", passes)
        replicas.round("child runs ended, runs ended", passes)

        val wall = started.elapsedNow()
        val ended = runs.map { (owner, run) -> server.stored(owner.org.id, run.id) }
        val orgs = owners.map { it.org.id }
        val visitKeys = server.count("workflow_runs_visits", orgs)
        val waits = server.count("automations_waits", orgs)
        val succeeded = "parent_run_id IS NOT NULL AND status = 'succeeded' AND "
        val children = server.count("workflow_runs_started", orgs, succeeded)
        val deadlocks = server.deadlocks() - deadlocksBefore
        val report = buildString {
            appendLine("$ORGS organizations x $RUNS_EACH runs (wait -> workflow), $REPLICAS replicas; pool 4")
            appendLine("${ended.count { it.status == RunStatus.SUCCEEDED }} of ${runs.size} runs succeeded in $wall")
            passes.times.forEach { (phase, times) -> appendLine(line(phase, times)) }
            appendLine("visit keys: $visitKeys (expected ${runs.size * 3}); waits: $waits; children ended: $children")
            appendLine("deadlocks: $deadlocks")
        }
        write("run-nodes-concurrency", report)
        assertEquals(List(runs.size) { RunStatus.SUCCEEDED }, ended.map { it.status }, report)
        assertEquals(List(runs.size) { listOf("ship", "next") }, ended.map { r -> r.visits.map { it.outcome } })
        assertEquals(runs.size * 3L, visitKeys, report)
        assertEquals(runs.size.toLong(), waits, report)
        assertEquals(runs.size.toLong(), children, report)
        assertEquals(0L, deadlocks, report)
    }

    // The workflow the workflow nodes call: a trigger, accepted.
    private suspend fun TestServer.childWorkflow(owner: Owner) {
        val document = """{"id":"build","name":"Build","domain":"git","nodes":[{"id":"t","kind":"trigger",""" +
            """"label":"Built"}],"edges":[],"start":"t","status":"accepted","foundAt":"2026-11-03T10:00:00Z"}"""
        val raw = OstromJson.parseToJsonElement(document).jsonObject
        koin.get<WorkflowsUseCase>().save(owner.org.id, raw, null, owner.login, true)
    }

    private suspend fun TestServer.answer(owner: Owner, run: WorkflowRun) {
        val waitId = Uuid.parse(assertNotNull(stored(owner.org.id, run.id).visits.last().waitId))
        val caller = WaitCaller(owner.org.id, owner.approver.toString(), admin = false, interactive = true)
        koin.get<AnswerWaitUseCase>().answer(caller, waitId, "ship", emptyMap())
    }

    // One step of every run: the replicas pass together, again until none finds a run due; each pass is timed.
    private suspend fun List<TickRunsUseCase>.round(phase: String, passes: Passes) {
        clock.now += STEP
        do {
            val played = coroutineScope {
                map { replica ->
                    async {
                        val mark = TimeSource.Monotonic.markNow()
                        replica.tick().also { passes.add(phase, mark.elapsedNow()) }
                    }
                }.awaitAll()
            }.sum()
        } while (played > 0)
    }

    private fun TestServer.engine(owner: String): TickRunsUseCase {
        val repository = koin.get<RunEngineRepository>()
        val play = PlayRunUseCaseImpl(repository, koin.get(), clock)
        return TickRunsUseCaseImpl(repository, play, clock, koin.get(), owner, BATCH)
    }

    private suspend fun TestServer.owner(login: String): Owner {
        val now = clock.now()
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)) { false }
        // The gates' approver, another member: whoever started a run never approves its gate alone (four eyes).
        val email = "$APPROVER@example.com"
        val approver = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, APPROVER, email, true, APPROVER, login = APPROVER),
        )
        val invitations = koin.get<InvitationsRepository>()
        val invitation =
            Invitation(Uuid.random(), org.id, null, OrgRole.MEMBER, user.id, now, now + 1.hours, null, 0, null)
        invitations.create(invitation, "hash-" + Uuid.random().toHexString())
        val member = Member(Uuid.random(), org.id, approver.id, OrgRole.MEMBER, now)
        assertTrue(invitations.accept(invitation, member, now))
        return Owner(login, org, member.id)
    }

    private suspend fun TestServer.queued(owner: Owner, graph: RunGraph = GRAPH): WorkflowRun {
        val now = clock.now() - 1.seconds
        val run = WorkflowRun(
            id = "wfr_" + Uuid.random().toHexString(),
            workflowId = "ship",
            workflowName = "Ship",
            workflowVersion = 1,
            status = RunStatus.QUEUED,
            startedBy = owner.login,
            source = "manual",
            inputs = mapOf("branch" to "main"),
            startedAt = now.isoMillis(),
        )
        koin.get<StartedRunsRepository>().add(owner.org.id, run, now, graph, RunAudit("started"))
        return run
    }

    private suspend fun TestServer.endSession(orgId: Uuid, id: Uuid) {
        val sessions = koin.get<CloudSessionsRepository>()
        val session = assertNotNull(sessions.get(orgId, id))
        assertNotNull(sessions.compareAndSet(session.copy(state = SessionState.PAUSED), null))
    }

    private suspend fun TestServer.approve(owner: Owner, run: WorkflowRun) {
        val gateId = Uuid.parse(assertNotNull(stored(owner.org.id, run.id).visits.last().gateId))
        val gates = koin.get<GatesRepository>()
        val gate = assertNotNull(gates.get(owner.org.id, gateId))
        val request = gates.requestsOf(owner.org.id, gateId).first { it.approverId == APPROVER }
        val actor = GateActor.person(APPROVER, owner.org.id, OrgRole.MEMBER, Clock.System.now())
        koin.get<AnswerGateUseCase>()(actor, request.id, Answer.YES, gate.shownDigest, AnswerChannel.CONSOLE)
    }

    private suspend fun TestServer.stored(orgId: Uuid, id: String): WorkflowRun =
        assertNotNull(koin.get<StartedRunsRepository>().find(orgId, id))

    private suspend fun TestServer.deadlocks(): Long = database.superuser.fetchAll(
        "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()",
    ).getOrThrow().first().get("deadlocks").asString().toLong()

    private suspend fun TestServer.count(table: String, orgs: List<Uuid>, where: String = ""): Long =
        database.superuser.fetchAll(
            "SELECT COUNT(*) AS n FROM $table WHERE $where org_id IN (" +
                orgs.joinToString { "CAST('$it' AS uuid)" } + ")",
        ).getOrThrow().first().get("n").asString().toLong()

    private fun write(name: String, report: String) {
        SystemFileSystem.createDirectories(Path("build/reports/perf"))
        SystemFileSystem.sink(Path("build/reports/perf/$name.txt")).buffered().use { it.writeString(report) }
        println(report)
    }

    private companion object {
        const val ORGS = 10
        const val RUNS_EACH = 10
        const val REPLICAS = 4
        const val BATCH = 50
        val STEP = 5.seconds

        const val APPROVER = "bea"

        val GRAPH = RunGraph(
            start = "build",
            nodes = listOf(
                WorkflowNode(
                    id = "build",
                    kind = WorkflowNodeKind.AGENT,
                    label = "Build",
                    run = NodeRun(instructions = "Fix the build of {{inputs.branch}}", agent = "claude"),
                ),
                WorkflowNode("review", WorkflowNodeKind.GATE, "Review", approvers = listOf(APPROVER)),
            ),
            edges = listOf(WorkflowEdge("build", "review"), WorkflowEdge("review", null, on = "approved")),
        )

        val NODES = RunGraph(
            start = "ask",
            nodes = listOf(
                WorkflowNode(
                    id = "ask",
                    kind = WorkflowNodeKind.WAIT,
                    label = "Ask",
                    approvers = listOf(APPROVER),
                    wait = WaitSpec("Ship it?", actions = listOf(WaitAction("ship", "Ship"))),
                ),
                WorkflowNode("call", WorkflowNodeKind.WORKFLOW, "Build", workflowId = "build"),
            ),
            edges = listOf(WorkflowEdge("ask", "call", on = "ship")),
        )

        fun line(phase: String, times: List<Duration>): String {
            val sorted = times.sorted()
            fun at(q: Double) = sorted[ceil(q * sorted.size).toInt() - 1]
            return "$phase: ${sorted.size} passes, p50 ${at(0.5)}, p95 ${at(0.95)}, max ${sorted.last()}"
        }
    }
}
