// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WaitAction
import run.ostrom.core.api.models.WaitField
import run.ostrom.core.api.models.WaitSpec
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.automations.AutomationsModule
import run.ostrom.server.automations.domain.AnswerWaitUseCase
import run.ostrom.server.automations.domain.WaitCaller
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.compiler.domain.models.Authorship
import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.launches.LaunchesModule
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.runners.RunnersModule
import run.ostrom.server.runners.domain.CloudPlanProvider
import run.ostrom.server.runners.domain.FreeCloudPlanProvider
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.workflowruns.domain.engine.PlayRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCase
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCaseImpl
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.ChildRequest
import run.ostrom.server.workflowruns.domain.services.RunChildren
import run.ostrom.server.workflowruns.domain.services.RunWaits
import run.ostrom.server.workflowruns.domain.services.WaitRequest
import run.ostrom.server.workflowruns.domain.services.WaitResult
import run.ostrom.server.workflowruns.domain.services.WaitState
import run.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCase
import run.ostrom.server.workflowruns.fakes.FixedClock
import run.ostrom.server.workflowruns.infrastructure.AutomationsRunWaits
import run.ostrom.server.workflowruns.infrastructure.StartedRunQueries
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The `wait` and `workflow` nodes on the real modules and PostgreSQL under the serving role: a wait node asks its
 * question through the waits and the run goes on with the answer, also on another replica after a restart; a workflow
 * node plays a child run of another workflow of the organization to its end, never itself, and a cancelled parent
 * cancels its child, whose wait is withdrawn.
 */
class RunNodesPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        RunnersModule, LaunchesModule, AutomationsModule, WorkflowRunsModule,
    )

    private val overrides = module { single<CloudPlanProvider> { FreeCloudPlanProvider() } }

    /** An organization: its owner, who starts the runs, and a member who answers their waits. */
    private class Team(val org: Org, val owner: Uuid, val approver: Uuid)

    private val clock = FixedClock(Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + 1_000))

    private fun nodesTest(block: suspend (TestServer) -> Unit) = serverTest(modules, overrides) { block(it) }

    private suspend fun TestServer.user(login: String): Uuid = koin.get<ResolveUserUseCase>()(
        VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
    ).id

    private suspend fun TestServer.team(owner: String): Team {
        val now = clock.now()
        val ownerId = user(owner)
        val org = Org(Uuid.random(), "$owner-team", "$owner team", null, null, now)
        val member = Member(Uuid.random(), org.id, ownerId, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        val approver = Member(Uuid.random(), org.id, user(APPROVER), OrgRole.MEMBER, now)
        val invitations = koin.get<InvitationsRepository>()
        val invitation =
            Invitation(Uuid.random(), org.id, null, OrgRole.MEMBER, ownerId, now, now + 1.hours, null, 0, null)
        invitations.create(invitation, "hash-" + Uuid.random().toHexString())
        assertTrue(invitations.accept(invitation, approver, now))
        return Team(org, member.id, approver.id)
    }

    /** Saves an accepted workflow of [team]'s organization. */
    @Suppress("LongParameterList") // A workflow document: its team, id, nodes, start, inputs and edges.
    private suspend fun TestServer.workflow(
        team: Team,
        id: String,
        nodes: String,
        start: String,
        inputs: String,
        edges: String = "[]",
    ) {
        val document = """{"id":"$id","name":"$id","domain":"git","nodes":$nodes,"edges":$edges,"start":"$start",""" +
            """"status":"accepted","foundAt":"2026-11-03T10:00:00Z","inputs":$inputs}"""
        val raw = OstromJson.parseToJsonElement(document).jsonObject
        koin.get<WorkflowsUseCase>().save(team.org.id, raw, null, OWNER, true)
    }

    /**
     * Stores an accepted workflow of [team]'s organization without the checks of a save: a save refuses a wait as the
     * start node, and these tests play the wait node itself.
     */
    @Suppress("LongParameterList") // A workflow document: its team, id, nodes, start, inputs and edges.
    private suspend fun TestServer.unchecked(
        team: Team,
        id: String,
        nodes: String,
        start: String,
        inputs: String,
        edges: String = "[]",
    ) {
        val document = """{"id":"$id","name":"$id","domain":"git","nodes":$nodes,"edges":$edges,"start":"$start",""" +
            """"status":"accepted","foundAt":"2026-11-03T10:00:00Z","inputs":$inputs}"""
        val raw = OstromJson.parseToJsonElement(document).jsonObject
        val author = Authorship(OWNER, clock.now())
        assertNotNull(koin.get<CompilerRepository>().saveWorkflow(team.org.id, id, raw, null, author))
    }

    private suspend fun TestServer.start(team: Team, workflowId: String): WorkflowRun =
        koin.get<StartWorkflowRunUseCase>()(owner(team), workflowId, mapOf("branch" to "main"))

    private fun owner(team: Team) = GateActor.person(OWNER, team.org.id, OrgRole.OWNER, Clock.System.now())

    private suspend fun TestServer.stored(team: Team, id: String): WorkflowRun =
        assertNotNull(koin.get<StartedRunsRepository>().find(team.org.id, id))

    /** An engine replica on the test's clock; each tick moves the clock past the look a waiting run asked for. */
    private fun TestServer.engine(owner: String = "engine-test"): TickRunsUseCase {
        val repository = koin.get<RunEngineRepository>()
        val play = PlayRunUseCaseImpl(repository, koin.get(), clock)
        return TickRunsUseCaseImpl(repository, play, clock, koin.get(), owner)
    }

    private suspend fun TickRunsUseCase.next(): Int {
        clock.now += 5.seconds
        return tick()
    }

    private suspend fun TestServer.audit(orgId: Uuid, runId: String): List<String> = database.superuser.fetchAll(
        "SELECT action FROM identity_audit_events WHERE org_id = CAST('$orgId' AS uuid) " +
            "AND target_type = 'workflow_run' AND target_id = '$runId' ORDER BY sequence",
    ).getOrThrow().map { it.get("action").asString() }

    private suspend fun TestServer.count(sql: String): Long =
        database.superuser.fetchAll(sql).getOrThrow().first().get("n").asString().toLong()

    @Test
    fun aWaitNodeAsksItsApproverAndTheRunGoesOnWithTheAnswerAlsoOnAnotherReplicaAfterARestart() = nodesTest { server ->
        val team = server.team("ada")
        server.unchecked(team, SHIP, ASK_NODES, "ask", BRANCH)
        val run = server.start(team, SHIP)

        assertEquals(1, server.engine().next())

        val waiting = server.stored(team, run.id)
        assertEquals(RunStatus.WAITING, waiting.status)
        val waitId = Uuid.parse(assertNotNull(waiting.visits.single().waitId))
        val wait = assertNotNull(server.koin.get<WaitsRepository>().get(team.org.id, waitId))
        assertEquals(
            listOf<Any?>(WaitStatus.OPEN, team.approver.toString(), team.owner.toString(), run.id, "Ship it?"),
            listOf(wait.status, wait.assignee, wait.createdBy, wait.runId, wait.question.title),
        )
        // Another replica, as after a restart, finds the same wait: nothing of the wait is held in memory.
        val restarted = server.engine("engine-after-restart")
        assertEquals(1, restarted.next())
        assertEquals(waiting, server.stored(team, run.id))
        assertEquals(1L, server.count("SELECT COUNT(*) AS n FROM automations_waits WHERE run_id = '${run.id}'"))
        val caller = WaitCaller(team.org.id, team.approver.toString(), admin = false, interactive = true)
        server.koin.get<AnswerWaitUseCase>().answer(caller, waitId, "ship", mapOf("note" to JsonPrimitive("go")))

        restarted.next()

        val ended = server.stored(team, run.id)
        assertEquals(RunStatus.SUCCEEDED, ended.status)
        assertEquals(listOf("ship" to """{"note":"go"}"""), ended.visits.map { it.outcome to it.output })
        assertEquals(
            listOf(
                "workflow_run.started", "workflow_run.node_started", "workflow_run.wait_asked",
                "workflow_run.ended",
            ),
            server.audit(team.org.id, run.id),
        )
    }

    @Test
    fun aCheckNodeChoosesItsBranchOnTheAnswerOfAWaitAndAMalformedConditionIsRefusedWhenSaved() = nodesTest { server ->
        val team = server.team("ada")
        val bad = CHECK_NODES.replace(""""step":"ask"""", """"step":"gone"""")
        val refused = assertFailsWith<OstromException> { server.workflow(team, "bad", bad, "ask", BRANCH, CHECK_EDGES) }
        assertEquals(ErrorKind.INVALID_REQUEST, refused.kind)
        assertEquals("Invalid workflow: node go: condition.step: gone is not a node of the workflow.", refused.message)
        server.unchecked(team, SHIP, CHECK_NODES, "ask", BRANCH, CHECK_EDGES)
        val run = server.start(team, SHIP)
        val engine = server.engine()
        engine.next()
        val waitId = Uuid.parse(assertNotNull(server.stored(team, run.id).visits.single().waitId))
        val caller = WaitCaller(team.org.id, team.approver.toString(), admin = false, interactive = true)
        server.koin.get<AnswerWaitUseCase>().answer(caller, waitId, "ship", mapOf("note" to JsonPrimitive("go")))

        engine.next()

        val ended = server.stored(team, run.id)
        assertEquals(RunStatus.SUCCEEDED, ended.status)
        val visits = ended.visits.map { it.nodeId to it.outcome }
        assertEquals(listOf("ask" to "ship", "go" to "true", "t" to "next"), visits)
    }

    @Test
    fun aWorkflowNodePlaysAChildRunOfAnotherWorkflowOfItsOrganizationToItsEnd() = nodesTest { server ->
        val team = server.team("ada")
        server.workflow(team, BUILD, """[{"id":"t","kind":"trigger","label":"Built"}]""", "t", TARGET)
        server.workflow(team, SHIP, CALL_BUILD, "build", BRANCH)
        val run = server.start(team, SHIP)
        val engine = server.engine()

        engine.next()

        val parent = server.stored(team, run.id)
        assertEquals(RunStatus.RUNNING, parent.status)
        val childId = assertNotNull(parent.visits.single().childRunId)
        val child = server.stored(team, childId)
        assertEquals(
            listOf<Any?>(BUILD, "workflow", run.id, OWNER, mapOf("target" to "main"), RunStatus.QUEUED),
            listOf(child.workflowId, child.source, child.parentRunId, child.startedBy, child.inputs, child.status),
        )
        // The visit started again (after a restart) finds its child: one child per visit.
        val children = server.koin.get<RunChildren>()
        assertEquals(childId, children.start(team.org.id, ChildRequest(parent, 1, BUILD, TARGET_X)).runId)
        val rows = "SELECT COUNT(*) AS n FROM workflow_runs_started WHERE org_id = CAST('${team.org.id}' AS uuid)"
        assertEquals(2L, server.count(rows))

        engine.next()
        engine.next()

        assertEquals(RunStatus.SUCCEEDED, server.stored(team, childId).status)
        val ended = server.stored(team, run.id)
        assertEquals(RunStatus.SUCCEEDED, ended.status)
        assertEquals(
            listOf(
                "workflow_run.started", "workflow_run.node_started", "workflow_run.child_started",
                "workflow_run.ended",
            ),
            server.audit(team.org.id, run.id),
        )
        assertEquals(
            listOf("workflow_run.started", "workflow_run.node_started", "workflow_run.ended"),
            server.audit(team.org.id, childId),
        )
        // Another organization neither reads nor cancels it.
        val eve = server.team("eve")
        assertNull(children.result(eve.org.id, childId))
        children.cancel(eve.org.id, childId)
        assertEquals(RunStatus.SUCCEEDED, server.stored(team, childId).status)
        // The child of a visit is found through its index.
        val plan = server.driver.withOrganization(team.org.id) { tx ->
            tx.execute("SET LOCAL enable_seqscan = off").getOrThrow()
            val explain = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${StartedRunQueries.CHILD}")
                .bind("org_id", team.org.id).bind("parent_run_id", run.id).bind("parent_seq", 1)
            tx.fetchAll(explain).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
        println("-- child of a visit\n$plan")
        assertTrue("workflow_runs_started_child" in plan, plan)
    }

    @Test
    fun aWorkflowThatCallsItselfFailsAndACancelledParentCancelsItsChildWhoseWaitIsWithdrawn() = nodesTest { server ->
        val team = server.team("ada")
        // A save refuses a workflow that calls itself: it is stored as an older version could have been.
        server.unchecked(team, LOOP, """[{"id":"again","kind":"workflow","label":"Again","workflowId":"$LOOP"}]""",
            "again", BRANCH)
        server.unchecked(team, ASK, ASK_NODES, "ask", BRANCH)
        server.workflow(team, SHIP, CALL_ASK, "call", BRANCH)
        val loop = server.start(team, LOOP)
        val ship = server.start(team, SHIP)
        val engine = server.engine()

        engine.next()
        engine.next()

        assertEquals(
            "Node again: failed (Workflow $LOOP already runs above this node: a workflow never calls itself.).",
            server.stored(team, loop.id).failure,
        )
        val childId = assertNotNull(server.stored(team, ship.id).visits.single().childRunId)
        val child = server.stored(team, childId)
        assertEquals(RunStatus.WAITING, child.status)
        val waitId = Uuid.parse(assertNotNull(child.visits.single().waitId))

        server.koin.get<CancelWorkflowRunUseCase>()(owner(team), ship.id, null)
        engine.next()
        engine.next()

        assertEquals(RunStatus.CANCELLED, server.stored(team, childId).status)
        assertEquals("workflow_run.cancelled", server.audit(team.org.id, childId).last())
        assertEquals(WaitStatus.CANCELLED, server.koin.get<WaitsRepository>().get(team.org.id, waitId)?.status)
    }

    @Test
    fun theWaitOfARunIsRefusedForAStarterOrAnAssigneeGoneOrAQuestionTheWaitsRefuse() = nodesTest { server ->
        val team = server.team("ada")
        val waits = server.koin.get<RunWaits>()
        suspend fun ask(starter: String = OWNER, assignee: String? = null, question: WaitSpec = QUESTION) =
            waits.ask(team.org.id, WaitRequest(RUN, 1, starter, assignee, question))

        assertEquals(STARTER_GONE, ask(starter = "ghost").refusal)
        assertEquals("nobody is not a member of the organization", ask(assignee = "nobody").refusal)
        val unknownType = QUESTION.copy(fields = listOf(WaitField("ok", "boolean", "OK")))
        assertEquals(
            "The wait node's question is malformed: a field type the waits do not know.",
            ask(question = unknownType).refusal,
        )
        assertEquals(
            "title is 1 to 200 characters, without control characters.",
            ask(question = QUESTION.copy(title = " ")).refusal,
        )
        // Asked of nobody in particular: the starter herself, once per visit.
        val mine = assertNotNull(ask().waitId)
        assertEquals(mine, ask().waitId)
        assertEquals(team.owner.toString(), server.koin.get<WaitsRepository>().get(team.org.id, mine)?.assignee)
        assertEquals(WaitResult(WaitState.OPEN), waits.result(team.org.id, mine))
        assertNull(waits.result(team.org.id, Uuid.random()))
        // A starter gone withdraws nothing; the starter withdraws it once, and again changes nothing.
        waits.cancel(team.org.id, "ghost", mine)
        assertEquals(WaitResult(WaitState.OPEN), waits.result(team.org.id, mine))
        waits.cancel(team.org.id, OWNER, mine)
        waits.cancel(team.org.id, OWNER, mine)
        assertEquals(WaitResult(WaitState.CANCELLED), waits.result(team.org.id, mine))
        // Another organization does not read it.
        assertNull(waits.result(server.team("eve").org.id, mine))
        // A colleague who is no longer a member asks nothing.
        val real = server.koin.get<LauncherResolver>()
        val removed = object : LauncherResolver by real {
            override suspend fun member(orgId: Uuid, userId: Uuid): OrgPrincipal? = null
        }
        val orphan = AutomationsRunWaits(server.koin.get(), server.koin.get(), server.koin.get(), removed)
        assertEquals(STARTER_GONE, orphan.ask(team.org.id, WaitRequest(RUN, 2, OWNER, null, QUESTION)).refusal)
    }

    private companion object {
        const val OWNER = "ada"
        const val APPROVER = "bea"
        const val SHIP = "ship"
        const val BUILD = "build"
        const val ASK = "ask"
        const val LOOP = "loop"
        const val RUN = "wfr_dddddddddddddddddddddddddddddddd"
        const val STARTER_GONE = "The person who started the run is no longer a member of the organization."
        const val BRANCH = """[{"id":"branch","label":"Branch","required":true}]"""
        const val TARGET = """[{"id":"target","label":"Target","required":true}]"""
        val TARGET_X = mapOf("target" to "x")
        const val ASK_NODES = """[{"id":"ask","kind":"wait","label":"Ask","approvers":["bea"],""" +
            """"wait":{"title":"Ship it?","fields":[{"id":"note","type":"text","label":"Note"}],""" +
            """"actions":[{"id":"ship","label":"Ship"},{"id":"hold","label":"Hold"}]}}]"""
        const val CALL_BUILD = """[{"id":"build","kind":"workflow","label":"Build","workflowId":"build",""" +
            """"arguments":{"target":"{{inputs.branch}}"}}]"""
        const val CALL_ASK = """[{"id":"call","kind":"workflow","label":"Call","workflowId":"ask",""" +
            """"arguments":{"branch":"{{inputs.branch}}"}}]"""
        val QUESTION = WaitSpec("Ship it?", actions = listOf(WaitAction("ship", "Ship")))
        val CHECK_NODES = ASK_NODES.removeSuffix("]") + """,{"id":"go","kind":"check","label":"Go?",""" +
            """"condition":{"step":"ask","path":"output.note","op":"equals","value":"go"}},""" +
            """{"id":"t","kind":"trigger","label":"Shipped"}]"""
        const val CHECK_EDGES = """[{"from":"ask","to":"go","on":"ship"},{"from":"ask","to":null,"on":"hold"},""" +
            """{"from":"go","to":"t","on":"true"},{"from":"go","to":null,"on":"false"}]"""
    }
}
