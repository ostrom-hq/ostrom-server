// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.automations.AutomationsModule
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.launches.LaunchesModule
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.launches.domain.usecases.OpenRunnerLaunchUseCase
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.runners.RunnersModule
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.CloudPlanProvider
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.FreeCloudPlanProvider
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.workflowruns.domain.engine.PlayRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.RunMoves
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCase
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.sessionOf
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.AgentRequest
import run.ostrom.server.workflowruns.domain.services.RunAgents
import run.ostrom.server.workflowruns.domain.services.RunGates
import run.ostrom.server.workflowruns.domain.services.StepSessions
import run.ostrom.server.workflowruns.fakes.FixedClock
import run.ostrom.server.workflowruns.infrastructure.CloudRunAgents
import run.ostrom.server.workflowruns.infrastructure.EngineQueries
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The engine that plays the runs of workflows, on the real modules and PostgreSQL under the serving role: an agent
 * node starts a cloud session of the run's starter, which the session's runner launches through the launch path; a
 * gate node opens an enforced gate and waits for its approver; the run ends `succeeded`, `failed` or `cancelled`, each
 * step written once, with its audit line, in its organization only.
 */
class RunEnginePostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        RunnersModule, LaunchesModule, AutomationsModule, WorkflowRunsModule,
    )

    // Cloud sessions are closed to every organization unless listed (OSTROM_RUNNERS_ALLOWED_ORGS): the free plan here.
    private val overrides = module { single<CloudPlanProvider> { FreeCloudPlanProvider() } }

    /** A member of a new organization, and a token of theirs that starts, cancels and reads the runs. */
    private class Person(val login: String, val org: Org, val userId: Uuid, val memberId: Uuid, val token: String)

    private val clock = FixedClock(Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + 1_000))

    private fun engineTest(block: suspend (TestServer) -> Unit) = serverTest(modules, overrides) { block(it) }

    private suspend fun TestServer.person(login: String, scopes: Set<String> = WRITE_SCOPES): Person {
        val now = clock.now()
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        approver(org, user.id, now)
        val secret = TokenKind.PERSONAL.prefix + "_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), user.id, org.id, "t", TokenKind.PERSONAL, scopes, "hint", null, now, now + 1.hours, null,
            null,
        )
        koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
        return Person(login, org, user.id, member.id, secret)
    }

    // The gate nodes' approver, a member of every organization here: whoever asked never approves alone (four eyes).
    private suspend fun TestServer.approver(org: Org, owner: Uuid, now: Instant) {
        val email = "$APPROVER@example.com"
        val identity = VerifiedIdentity(IdentityProvider.GITHUB, APPROVER, email, true, APPROVER, login = APPROVER)
        val user = koin.get<ResolveUserUseCase>()(identity)
        val invitations = koin.get<InvitationsRepository>()
        val invitation =
            Invitation(Uuid.random(), org.id, null, OrgRole.MEMBER, owner, now, now + 1.hours, null, 0, null)
        invitations.create(invitation, "hash-" + Uuid.random().toHexString())
        assertTrue(invitations.accept(invitation, Member(Uuid.random(), org.id, user.id, OrgRole.MEMBER, now), now))
    }

    private suspend fun TestServer.call(token: String, path: String, body: String? = null): HttpResponse =
        if (body == null) {
            client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
        } else {
            client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }

    /** Saves an accepted workflow of [person]'s organization with these nodes and edges. */
    private suspend fun TestServer.workflow(person: Person, nodes: String, edges: String, start: String) {
        val document = """{"id":"$SHIP","name":"Ship","domain":"git","nodes":$nodes,"edges":$edges,""" +
            """"start":"$start","status":"accepted","foundAt":"2026-11-03T10:00:00Z",""" +
            """"inputs":[{"id":"branch","label":"Branch","required":true}]}"""
        val raw = OstromJson.parseToJsonElement(document).jsonObject
        koin.get<WorkflowsUseCase>().save(person.org.id, raw, null, person.login, true)
    }

    private suspend fun TestServer.start(person: Person): WorkflowRun {
        val started = call(person.token, "/api/v1/workflows/$SHIP/runs", """{"inputs":{"branch":"main"}}""")
        assertEquals(HttpStatusCode.Created, started.status, started.bodyAsText())
        return started.run()
    }

    private suspend fun TestServer.read(person: Person, id: String): WorkflowRun {
        val read = call(person.token, "/api/v1/runs/$id")
        assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
        return read.run()
    }

    /** A queued run of [orgId], stored as a start stores it, with [graph]. */
    private suspend fun TestServer.queued(orgId: Uuid, graph: RunGraph, startedBy: String = "ada"): WorkflowRun {
        val now = clock.now() - 1.seconds
        val run = WorkflowRun(
            id = "wfr_" + Uuid.random().toHexString(),
            workflowId = SHIP,
            workflowName = "Ship",
            workflowVersion = 1,
            status = RunStatus.QUEUED,
            startedBy = startedBy,
            source = "manual",
            inputs = mapOf("branch" to "main"),
            startedAt = now.isoMillis(),
        )
        koin.get<StartedRunsRepository>().add(orgId, run, now, graph, RunAudit("started"))
        return run
    }

    private suspend fun TestServer.stored(orgId: Uuid, id: String): WorkflowRun =
        assertNotNull(koin.get<StartedRunsRepository>().find(orgId, id))

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

    // The session ends as the runner reports it: no execution held any more.
    private suspend fun TestServer.endSession(orgId: Uuid, id: Uuid, state: SessionState = SessionState.PAUSED) {
        val sessions = koin.get<CloudSessionsRepository>()
        val session = assertNotNull(sessions.get(orgId, id))
        assertNotNull(sessions.compareAndSet(session.copy(state = state), null))
    }

    private suspend fun TestServer.answer(person: Person, gateId: Uuid, answer: Answer) {
        val gates = koin.get<GatesRepository>()
        val gate = assertNotNull(gates.get(person.org.id, gateId))
        val request = gates.requestsOf(person.org.id, gateId).first { it.approverId == APPROVER }
        val actor = GateActor.person(APPROVER, person.org.id, OrgRole.MEMBER, Clock.System.now())
        koin.get<AnswerGateUseCase>()(actor, request.id, answer, gate.shownDigest, AnswerChannel.CONSOLE)
    }

    private suspend fun TestServer.audit(orgId: Uuid, runId: String): List<String> = database.superuser.fetchAll(
        "SELECT action FROM identity_audit_events WHERE org_id = CAST('$orgId' AS uuid) " +
            "AND target_type = 'workflow_run' AND target_id = '$runId' ORDER BY sequence",
    ).getOrThrow().map { it.get("action").asString() }

    private suspend fun TestServer.visitKeys(runId: String): Long = database.superuser.fetchAll(
        "SELECT COUNT(*) AS n FROM workflow_runs_visits WHERE run_id = '$runId'",
    ).getOrThrow().first().get("n").asString().toLong()

    private suspend fun HttpResponse.run(): WorkflowRun =
        OstromJson.decodeFromString(WorkflowRun.serializer(), bodyAsText())

    @Test
    fun anAgentNodeStartsACloudSessionOfTheStarterThatItsRunnerLaunchesThroughTheLaunchPath() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, AGENT_THEN_GATE, AGENT_THEN_GATE_EDGES, "build")
        val run = server.start(ada)
        assertEquals(RunStatus.QUEUED, run.status)

        assertEquals(1, server.engine().next())

        val playing = server.read(ada, run.id)
        assertEquals(RunStatus.RUNNING, playing.status)
        assertEquals("build", playing.currentNodeId)
        val visit = playing.visits.single()
        assertEquals(
            listOf(1, "build", WorkflowNodeKind.AGENT, "running"),
            listOf(visit.seq, visit.nodeId, visit.kind, visit.status),
        )
        val sessionId = sessionOf(run.id, 1)
        assertEquals(sessionId.toString(), visit.sessionId)
        assertEquals(sessionId.toString(), playing.sessionId)
        val session = assertNotNull(server.koin.get<CloudSessionsRepository>().get(ada.org.id, sessionId))
        assertEquals(ada.memberId.toString(), session.ownerId)
        assertEquals(AgentKind.CLAUDE, session.agent)
        assertEquals("Fix the build of main", session.prompt)
        assertEquals("claude-opus-5-5", session.model)
        assertEquals("Ship · Build", session.name)
        assertEquals(DesiredState.RUNNING, session.desiredState)
        // The session's runner opens its launch as for any cloud session: for the starter, under the policy.
        val launch = server.koin.get<OpenRunnerLaunchUseCase>()(session).launch
        assertEquals(
            listOf<Any?>(ada.userId, "claude-code", sessionId),
            listOf(launch.userId, launch.agent, launch.cloudSessionId),
        )
        // While the agent works, the run waits for it and the session is not started twice.
        assertEquals(1, server.engine().next())
        assertEquals(RunStatus.RUNNING, server.read(ada, run.id).status)
        assertEquals(
            listOf("workflow_run.started", "workflow_run.node_started"),
            server.audit(ada.org.id, run.id),
        )
    }

    @Test
    fun aGateNodeOpensAnEnforcedGateOfItsApproversOnceTheAgentEnded() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, AGENT_THEN_GATE, AGENT_THEN_GATE_EDGES, "build")
        val run = server.start(ada)
        val engine = server.engine()
        engine.next()
        server.endSession(ada.org.id, sessionOf(run.id, 1))

        engine.next()

        val waiting = server.read(ada, run.id)
        assertEquals(RunStatus.WAITING, waiting.status)
        assertEquals("review", waiting.currentNodeId)
        val (agent, gate) = waiting.visits
        assertEquals(listOf("done", "next"), listOf(agent.status, agent.outcome))
        assertEquals(
            listOf(2, "review", WorkflowNodeKind.GATE, "waiting"),
            listOf(gate.seq, gate.nodeId, gate.kind, gate.status),
        )
        val gateId = Uuid.parse(assertNotNull(gate.gateId))
        val opened = assertNotNull(server.koin.get<GatesRepository>().get(ada.org.id, gateId))
        assertEquals(GateStatus.PENDING, opened.status)
        assertEquals(setOf(AskedVia.WORKFLOW), opened.askedVia)
        assertEquals(listOf("ada"), opened.askedBy)
        assertEquals(run.id, opened.subject.runId)
        val node = assertNotNull(opened.node)
        assertEquals(listOf<Any>(SHIP, "review", true), listOf(node.workflowId, node.nodeId, node.enforced))
        val approvers = server.koin.get<GatesRepository>().requestsOf(ada.org.id, gateId).map { it.approverId }
        assertEquals(listOf(APPROVER), approvers)
        // A look while nobody answered changes nothing: the same gate, still waiting.
        engine.next()
        assertEquals(waiting, server.read(ada, run.id))
        assertEquals(
            listOf(
                "workflow_run.started", "workflow_run.node_started", "workflow_run.node_started",
                "workflow_run.gate_opened",
            ),
            server.audit(ada.org.id, run.id),
        )
    }

    @Test
    fun anAgentThenAGateApprovedEndsTheRunSucceeded() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, AGENT_THEN_GATE, AGENT_THEN_GATE_EDGES, "build")
        val run = server.start(ada)
        val engine = server.engine()
        engine.next()
        server.endSession(ada.org.id, sessionOf(run.id, 1))
        engine.next()
        val gateId = Uuid.parse(assertNotNull(server.read(ada, run.id).visits.last().gateId))

        server.answer(ada, gateId, Answer.YES)
        engine.next()

        val ended = server.read(ada, run.id)
        assertEquals(RunStatus.SUCCEEDED, ended.status)
        assertNull(ended.currentNodeId)
        assertNull(ended.failure)
        assertNotNull(ended.endedAt)
        assertEquals(listOf("next", "approved"), ended.visits.map { it.outcome })
        assertEquals(listOf("done", "done"), ended.visits.map { it.status })
        assertEquals(2L, server.visitKeys(run.id))
        assertEquals(
            listOf(
                "workflow_run.started", "workflow_run.node_started", "workflow_run.node_started",
                "workflow_run.gate_opened", "workflow_run.ended",
            ),
            server.audit(ada.org.id, run.id),
        )
        // Nothing is left to play: no replica leases it again.
        assertEquals(0, engine.next())
        assertEquals(ended, server.read(ada, run.id))
    }

    @Test
    fun aGateRejectedFailsTheRunWhenNoEdgeTakesTheNo() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, GATE_ONLY, "[]", "review")
        val run = server.start(ada)
        val engine = server.engine()
        engine.next()
        server.answer(ada, Uuid.parse(assertNotNull(server.read(ada, run.id).visits.single().gateId)), Answer.NO)

        engine.next()

        val ended = server.read(ada, run.id)
        assertEquals(RunStatus.FAILED, ended.status)
        assertEquals("Node review: rejected.", ended.failure)
        assertEquals("rejected", ended.visits.single().outcome)
    }

    @Test
    fun aRunEndsWhenItsAgentFailsOrTheGateIsRefusedOrItsNodeIsNotPlayedYet() = engineTest { server ->
        val ada = server.person("ada")
        val engine = server.engine()
        val failedAgent = server.queued(ada.org.id, RunGraph("a", listOf(AGENT)))
        val noApprover = server.queued(ada.org.id, RunGraph("g", listOf(WorkflowNode("g", WorkflowNodeKind.GATE, "G"))))
        val step = server.queued(ada.org.id, RunGraph("s", listOf(WorkflowNode("s", WorkflowNodeKind.STEP, "S"))))
        val legacy = server.queued(ada.org.id, RunGraph())
        // A run started before the server played runs has no graph at all.
        server.database.superuser.fetchAll(
            "UPDATE workflow_runs_started SET graph = NULL WHERE id = '${legacy.id}' RETURNING id",
        ).getOrThrow()
        val ghost = server.queued(ada.org.id, RunGraph("a", listOf(AGENT)), startedBy = "ghost")
        engine.next()
        server.endSession(ada.org.id, sessionOf(failedAgent.id, 1), SessionState.FAILED)

        engine.next()

        suspend fun ended(run: WorkflowRun) = server.stored(ada.org.id, run.id)
        assertEquals("Node a: failed (The agent session failed to start.).", ended(failedAgent).failure)
        assertEquals(RunStatus.FAILED, ended(noApprover).status)
        assertTrue(ended(noApprover).visits.single().output.orEmpty().isNotEmpty())
        assertEquals("Node s: failed (The server does not play step nodes yet.).", ended(step).failure)
        assertEquals(RunMoves.NOTHING_TO_PLAY, ended(legacy).failure)
        assertEquals(
            "Node a: failed (The person who started the run is no longer a member of the organization.).",
            ended(ghost).failure,
        )
    }

    @Test
    fun aPlanThatRefusesTheSessionFailsTheAgentNode() = serverTest(
        modules,
        module { single<CloudPlanProvider> { PlanOf(CloudPlan(0, 300, 30, 240, true, true)) } },
    ) { server ->
        val ada = server.person("ada")
        val run = server.queued(ada.org.id, RunGraph("a", listOf(AGENT)))

        server.engine().next()

        val ended = server.stored(ada.org.id, run.id)
        assertEquals(RunStatus.FAILED, ended.status)
        assertTrue(ended.visits.single().output.orEmpty().isNotEmpty(), ended.toString())
        assertNull(server.koin.get<CloudSessionsRepository>().get(ada.org.id, sessionOf(run.id, 1)))
    }

    @Test
    fun aNodeVisitedMoreThanItsMaxVisitsFailsTheRun() = engineTest { server ->
        val ada = server.person("ada")
        val once = server.queued(ada.org.id, RunGraph("t", listOf(TRIGGER), listOf(WorkflowEdge("t", "t"))))
        val thrice = server.queued(
            ada.org.id,
            RunGraph("t", listOf(TRIGGER.copy(maxVisits = 3)), listOf(WorkflowEdge("t", "t", on = "next"))),
        )
        val ends = server.queued(ada.org.id, RunGraph("t", listOf(TRIGGER), listOf(WorkflowEdge("t", null))))
        val missing = server.queued(ada.org.id, RunGraph("t", listOf(TRIGGER), listOf(WorkflowEdge("t", "nowhere"))))

        server.engine().next()

        assertEquals("Node t was visited 1 time(s), its maxVisits.", server.stored(ada.org.id, once.id).failure)
        val looped = server.stored(ada.org.id, thrice.id)
        assertEquals("Node t was visited 3 time(s), its maxVisits.", looped.failure)
        assertEquals(3, looped.visits.size)
        assertEquals(3L, server.visitKeys(thrice.id))
        assertEquals(RunStatus.SUCCEEDED, server.stored(ada.org.id, ends.id).status)
        assertEquals("Node nowhere is not in the workflow.", server.stored(ada.org.id, missing.id).failure)
    }

    @Test
    fun anOwnerCancelsARunAndTheEngineEndsItsAgentSession() = engineTest { server ->
        val ada = server.person("ada")
        val reader = server.person("rex", setOf("workflows:read"))
        server.workflow(ada, AGENT_THEN_GATE, AGENT_THEN_GATE_EDGES, "build")
        val run = server.start(ada)
        val engine = server.engine()
        engine.next()

        val refused = server.call(reader.token, "/api/v1/runs/${run.id}/cancel", "{}")
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        val cancelled = server.call(ada.token, "/api/v1/runs/${run.id}/cancel", "{}")
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        val answer = cancelled.run()
        assertEquals(RunStatus.CANCELLED, answer.status)
        assertNull(answer.currentNodeId)
        assertNotNull(answer.endedAt)
        assertEquals(listOf("failed", "cancelled"), answer.visits.single().let { listOf(it.status, it.outcome) })
        assertEquals(answer, server.read(ada, run.id))

        assertEquals(1, engine.next())

        val session = assertNotNull(server.koin.get<CloudSessionsRepository>().get(ada.org.id, sessionOf(run.id, 1)))
        assertEquals(DesiredState.TERMINATED, session.desiredState)
        assertEquals(answer, server.read(ada, run.id))
        assertEquals(0, engine.next())
        val again = server.call(ada.token, "/api/v1/runs/${run.id}/cancel", "{}")
        assertEquals(HttpStatusCode.Conflict, again.status, again.bodyAsText())
        assertEquals("workflow_run.cancelled", server.audit(ada.org.id, run.id).last())
    }

    @Test
    fun theStarterDeliversTheAgentStepOnceAndTheRunEndsWithItsDelivery() = engineTest { server ->
        val ada = server.person("ada")
        val reader = server.person("rex", setOf("workflows:read"))
        val eve = server.person("eve")
        server.workflow(ada, DELIVERING, DELIVERING_EDGES, "build")
        val run = server.start(ada)
        val engine = server.engine()
        val deliver = "/api/v1/runs/${run.id}/deliver"
        val early = server.call(ada.token, deliver, """{"output":"https://github.com/acme/api/pull/7"}""")
        assertEquals(HttpStatusCode.Conflict, early.status, "Nothing is asked before the step's session exists")
        engine.next()
        val sessions = server.koin.get<CloudSessionsRepository>()
        val instructions = assertNotNull(sessions.get(ada.org.id, sessionOf(run.id, 1)))
        assertTrue(instructions.prompt.orEmpty().endsWith("as its `output`, then end your turn."), instructions.prompt)
        val pr = "https://github.com/acme/api/pull/" + "7".repeat(600)

        assertEquals(HttpStatusCode.Unauthorized, server.client.post(deliver).status)
        val body = """{"output":"$pr"}"""
        assertEquals(HttpStatusCode.Forbidden, server.call(reader.token, deliver, body).status)
        assertEquals(HttpStatusCode.NotFound, server.call(eve.token, deliver, body).status)
        assertEquals(HttpStatusCode.NotFound, server.call(ada.token, "/api/v1/runs/wfr_unknown/deliver", body).status)
        val wrong = server.call(ada.token, deliver, """{"output":"https://gitlab.com/acme/api/-/merge_requests/7"}""")
        assertEquals(HttpStatusCode.BadRequest, wrong.status, wrong.bodyAsText())
        assertTrue("invalid_deliverable" in wrong.bodyAsText(), wrong.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, server.call(ada.token, deliver, "{}").status)

        val delivered = server.call(ada.token, deliver, body)

        assertEquals(HttpStatusCode.OK, delivered.status, delivered.bodyAsText())
        val visit = delivered.run().visits.single()
        assertEquals(
            listOf<Any?>("done", "delivered", pr, "Build"),
            listOf(visit.status, visit.outcome, visit.output, visit.nodeTitle),
        )
        val again = server.call(ada.token, deliver, body)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertTrue("nothing_to_deliver" in again.bodyAsText(), again.bodyAsText())
        engine.next()
        val ended = server.read(ada, run.id)
        assertEquals(listOf<Any?>(RunStatus.SUCCEEDED, pr), listOf(ended.status, ended.output))
        assertTrue("workflow_run.delivered" in server.audit(ada.org.id, run.id))
        assertEquals(HttpStatusCode.Conflict, server.call(ada.token, deliver, body).status, "The run ended")
    }

    @Test
    fun theSessionsAgentTokenOrItsStarterDeliversForTheStepTheSessionWorksOnAndThePendingStepIsTold() =
        engineTest { server ->
            val ada = server.person("ada")
            val reader = server.person("rex", setOf("workflows:read"))
            val eve = server.person("eve")
            server.workflow(ada, DELIVERING, DELIVERING_EDGES, "build")
            val run = server.start(ada)
            val engine = server.engine()
            engine.next()
            val session = sessionOf(run.id, 1)
            val deliver = "/api/v1/workflows/session/deliver"
            val pending = "/api/v1/workflows/session/pending"
            val pr = "https://github.com/acme/api/pull/9"
            val waiting = """{"needsAction":true,"runId":"${run.id}","nodeTitle":"Build",""" +
                """"deliverable":{"type":"url","domain":"github.com"}}"""
            val none = """{"needsAction":false}"""
            suspend fun pendingOf(token: String, query: String = "?sessionId=$session"): Pair<HttpStatusCode, String> {
                val answer = server.call(token, pending + query)
                return answer.status to answer.bodyAsText()
            }

            // The starter names the session; nobody else learns it exists.
            assertEquals(HttpStatusCode.OK to waiting, pendingOf(ada.token))
            assertEquals(HttpStatusCode.Unauthorized, server.client.get(pending).status)
            assertEquals(HttpStatusCode.Unauthorized, server.client.post(deliver).status)
            assertEquals(HttpStatusCode.Forbidden, pendingOf(reader.token).first)
            assertEquals(HttpStatusCode.NotFound, pendingOf(eve.token).first, "Another organization's session")
            assertEquals(HttpStatusCode.NotFound, pendingOf(ada.token, "?sessionId=${Uuid.random()}").first)
            assertEquals(HttpStatusCode.NotFound, pendingOf(ada.token, "?sessionId=s-1").first)
            assertEquals(HttpStatusCode.BadRequest, pendingOf(ada.token, "").first)
            val body = """{"sessionId":"$session","output":"$pr"}"""
            assertEquals(HttpStatusCode.NotFound, server.call(eve.token, deliver, body).status)
            // A service key never delivers, whatever its scopes.
            val key = TokenKind.SERVICE.prefix + "_" + Uuid.random().toHexString()
            val now = clock.now()
            val service = ApiToken(
                Uuid.random(), ada.userId, ada.org.id, "ci", TokenKind.SERVICE, WRITE_SCOPES, "hint", null, now,
                now + 1.hours, null, null,
            )
            server.koin.get<ApiTokensRepository>().create(service, server.koin.get<SecretHasher>().hash(key))
            val refused = server.call(key, deliver, body)
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertTrue("credential_scope" in refused.bodyAsText(), refused.bodyAsText())

            // The session's runner opens its launch: its agent token acts on its own session only.
            val cloud = server.koin.get<CloudSessionsRepository>()
            val agent = server.koin.get<OpenRunnerLaunchUseCase>()(assertNotNull(cloud.get(ada.org.id, session)))
            val token = agent.agentToken
            assertEquals(HttpStatusCode.OK to waiting, pendingOf(token, "?sessionId=${Uuid.random()}"))
            assertEquals(HttpStatusCode.Forbidden, server.call(token, "/api/v1/runs/${run.id}").status)
            assertEquals(HttpStatusCode.Forbidden, server.call(token, "/api/v1/runs/${run.id}/deliver", body).status)
            // Its agent asks a person something: the step is not pending while the question is open.
            assertEquals(HttpStatusCode.OK, server.call(token, "/api/v1/sessions", SEGMENT).status)
            val steps = server.koin.get<StepSessions>()
            assertFalse(steps.asked(ada.org.id, session))
            val question = WaitSpec("Which base?", actions = listOf(WaitAction("ok", "OK")))
            val wait = Wait(
                Uuid.random(), ada.org.id, "human_request", WaitStatus.OPEN, "s-1", null, ada.memberId.toString(),
                ada.memberId.toString(), question, null, now, null, null,
            )
            server.koin.get<WaitsRepository>().createWithinLimit(wait, 5)
            assertTrue(steps.asked(ada.org.id, session))
            assertFalse(steps.asked(eve.org.id, session), "Another organization's")
            assertEquals(HttpStatusCode.OK to none, pendingOf(token))
            assertEquals(
                listOf(true, false, false, false),
                listOf("ada", "nobody").map { steps.ownedBy(ada.org.id, session, it) } +
                    listOf(steps.ownedBy(ada.org.id, Uuid.random(), "ada"), steps.ownedBy(eve.org.id, session, "ada")),
            )
            // A token issued before deliveries does not deliver.
            server.database.superuser.execute(
                "UPDATE identity_api_tokens SET scopes = 'agent:session' WHERE kind = 'AGENT' " +
                    "AND org_id = CAST('${ada.org.id}' AS uuid)",
            ).getOrThrow()
            val old = server.call(token, deliver, """{"output":"$pr"}""")
            assertEquals(HttpStatusCode.Forbidden, old.status)
            assertTrue("credential_scope" in old.bodyAsText(), old.bodyAsText())
            server.database.superuser.execute(
                "UPDATE identity_api_tokens SET scopes = 'agent:session workflow:session' WHERE kind = 'AGENT' " +
                    "AND org_id = CAST('${ada.org.id}' AS uuid)",
            ).getOrThrow()

            val delivered = server.call(token, deliver, """{"sessionId":"${Uuid.random()}","output":"$pr"}""")

            assertEquals(HttpStatusCode.OK, delivered.status, delivered.bodyAsText())
            val visit = delivered.run().visits.single()
            assertEquals(listOf<String?>("delivered", pr), listOf(visit.outcome, visit.output))
            val again = server.call(ada.token, deliver, body)
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertTrue("nothing_to_deliver" in again.bodyAsText(), again.bodyAsText())
            assertEquals(HttpStatusCode.OK to none, pendingOf(ada.token))
            // The caller's own session that runs no step is nothing to deliver, not unknown.
            val own = Uuid.random()
            val step = assertNotNull(cloud.get(ada.org.id, session))
            val mine = step.copy(id = own, runnerTokenHash = null, runnerId = null)
            assertTrue(cloud.createWithinCap(mine, RUNS))
            assertEquals(HttpStatusCode.OK to none, pendingOf(ada.token, "?sessionId=$own"))
            val nothing = server.call(ada.token, deliver, """{"sessionId":"$own","output":"$pr"}""")
            assertEquals(HttpStatusCode.Conflict, nothing.status, nothing.bodyAsText())
            // The run of a session is found through the visits' session index.
            val plan = server.driver.withOrganization(ada.org.id) { tx ->
                tx.execute("SET LOCAL enable_seqscan = off").getOrThrow()
                val explain = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${EngineQueries.OF_SESSION}")
                    .bind("org_id", ada.org.id).bind("session_id", session)
                tx.fetchAll(explain).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
            }
            println("-- run of a session\n$plan")
            assertTrue("workflow_runs_visits_session" in plan, plan)
        }

    @Test
    fun anAgentStepWhoseSessionStopsWithNothingDeliveredFailsEndedWithoutDelivery() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, DELIVERING, DELIVERING_EDGES, "build")
        val run = server.start(ada)
        val engine = server.engine()
        engine.next()
        server.endSession(ada.org.id, sessionOf(run.id, 1))

        engine.next()

        val ended = server.read(ada, run.id)
        assertEquals(listOf<Any?>(RunStatus.FAILED, "ended_without_delivery"), listOf(ended.status, ended.failureCode))
        assertEquals("Node build: failed (${RunMoves.ENDED_WITHOUT_DELIVERY}).", ended.failure)
    }

    @Test
    fun anotherOrganizationNeitherReadsNorCancelsARun() = engineTest { server ->
        val ada = server.person("ada")
        val eve = server.person("eve")
        server.workflow(ada, GATE_ONLY, "[]", "review")
        val run = server.start(ada)

        assertEquals(HttpStatusCode.NotFound, server.call(eve.token, "/api/v1/runs/${run.id}").status)
        assertEquals(HttpStatusCode.NotFound, server.call(eve.token, "/api/v1/runs/${run.id}/cancel", "{}").status)
        assertEquals(HttpStatusCode.NotFound, server.call(ada.token, "/api/v1/runs/wfr_unknown").status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/runs/${run.id}").status)
        assertEquals(RunStatus.QUEUED, server.read(ada, run.id).status)
        // The engine plays each organization's runs in its own: Eve's organization holds no visit, no gate of Ada's.
        server.engine().next()
        assertEquals(RunStatus.WAITING, server.read(ada, run.id).status)
        assertEquals(HttpStatusCode.NotFound, server.call(eve.token, "/api/v1/runs/${run.id}").status)
    }

    @Test
    fun aRunIsLeasedToOneReplicaAndAChangeIsWrittenOnlyOnTheRunAsRead() = engineTest { server ->
        val ada = server.person("ada")
        val run = server.queued(ada.org.id, RunGraph("t", listOf(TRIGGER)))
        val repository = server.koin.get<RunEngineRepository>()
        val now = clock.now()

        val first = repository.claimDue("one", now, now + 60.seconds, 10)
        assertEquals(listOf(run.id), first.map { it.id })
        assertEquals(emptyList(), repository.claimDue("two", now, now + 60.seconds, 10))
        val begin = RunMoves("one", now).start(run, assertNotNull(repository.played(ada.org.id, run.id)).graph)
        // Another replica's change, a change of a run that moved, and a second begin of the same visit are dropped.
        assertFalse(repository.write(ada.org.id, begin.copy(owner = "two")))
        assertFalse(repository.write(ada.org.id, begin.copy(expected = begin.next)))
        assertFalse(repository.write(Uuid.random(), begin))
        assertTrue(repository.write(ada.org.id, begin))
        assertFalse(repository.write(ada.org.id, RunChange(begin.next, begin.next, "one", begin.visit)))
        assertEquals(1L, server.visitKeys(run.id))
        // A lease that lapsed (the replica stopped) is taken by another, which goes on from the stored run.
        repository.release(ada.org.id, run.id, "two", null)
        assertEquals(listOf(run.id), repository.claimDue("two", now + 61.seconds, now + 120.seconds, 10).map { it.id })
        repository.release(ada.org.id, run.id, "two", null)
        assertEquals(emptyList(), repository.claimDue("one", now + 200.seconds, now + 260.seconds, 10))
        assertNull(repository.played(eveOrg, run.id))
        // A gate or a session another organization holds, or none, is not there for this one.
        assertNull(server.koin.get<RunGates>().state(ada.org.id, Uuid.random()))
        assertNull(server.koin.get<RunAgents>().state(eveOrg, sessionOf(run.id, 1)))
        // A starter whose login still resolves but whose membership is gone (removed meanwhile) starts no session.
        val real = server.koin.get<LauncherResolver>()
        val removed = object : LauncherResolver by real {
            override suspend fun member(orgId: Uuid, userId: Uuid): OrgPrincipal? = null
        }
        val agents = CloudRunAgents(server.koin.get(), server.koin.get(), removed, clock)
        val request = AgentRequest(Uuid.random(), "ada", "Ship · Build", "claude", null, "Build")
        assertEquals(
            "The person who started the run is no longer a member of the organization.",
            agents.start(ada.org.id, request),
        )
        assertNull(server.koin.get<CloudSessionsRepository>().get(ada.org.id, request.sessionId))
    }

    @Test
    fun twoReplicasPlayingTheSameRunsBeginEachVisitOnce() = engineTest { server ->
        val ada = server.person("ada")
        val graph = RunGraph(
            "t",
            listOf(TRIGGER.copy(maxVisits = 5), WorkflowNode("u", WorkflowNodeKind.TRIGGER, "U")),
            listOf(WorkflowEdge("t", "t"), WorkflowEdge("t", "u", on = "never")),
        )
        val runs = List(RUNS) { server.queued(ada.org.id, graph) }

        val leased = coroutineScope {
            listOf("one", "two").map { owner -> async { server.engine(owner).tick() } }.awaitAll()
        }

        assertEquals(RUNS, leased.sum())
        runs.forEach { run ->
            val ended = server.stored(ada.org.id, run.id)
            assertEquals(RunStatus.FAILED, ended.status)
            assertEquals(5, ended.visits.size)
            assertEquals(5L, server.visitKeys(run.id))
        }
    }

    @Test
    fun theModuleStartsTheEngineThatPlaysAStartedRun() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, GATE_ONLY, "[]", "review")
        val run = server.start(ada)
        val job = Job()

        WorkflowRunsModule.start(CoroutineScope(job), server.koin)

        try {
            withTimeout(20.seconds) {
                while (server.read(ada, run.id).status != RunStatus.WAITING) delay(50.milliseconds)
            }
        } finally {
            job.cancel()
        }
        val gateId = Uuid.parse(assertNotNull(server.read(ada, run.id).visits.single().gateId))
        assertEquals(GateStatus.PENDING, server.koin.get<GatesRepository>().get(ada.org.id, gateId)?.status)
    }

    /** A plan provider that answers one plan. */
    private class PlanOf(private val plan: CloudPlan) : CloudPlanProvider {
        override suspend fun planFor(orgId: Uuid): CloudPlan = plan
    }

    private companion object {
        const val SHIP = "ship"
        const val RUNS = 20
        val WRITE_SCOPES = setOf("workflows:read", "workflows:write")
        val eveOrg: Uuid = Uuid.parse("0000000e-0000-0000-0000-000000000000")
        val TRIGGER = WorkflowNode("t", WorkflowNodeKind.TRIGGER, "Push")
        val AGENT = WorkflowNode("a", WorkflowNodeKind.AGENT, "Agent", run = NodeRun(instructions = "Do it"))
        const val APPROVER = "bea"
        const val AGENT_THEN_GATE = """[{"id":"build","kind":"agent","label":"Build",""" +
            """"run":{"instructions":"Fix the build of {{inputs.branch}}","agent":"claude",""" +
            """"model":"claude-opus-5-5"}},""" +
            """{"id":"review","kind":"gate","label":"Review","approvers":["bea"]}]"""
        const val AGENT_THEN_GATE_EDGES =
            """[{"from":"build","to":"review"},{"from":"review","to":null,"on":"approved"}]"""
        const val DELIVERING = """[{"id":"build","kind":"agent","label":"Build",""" +
            """"run":{"instructions":"Open a pull request on {{inputs.branch}}",""" +
            """"deliverable":{"type":"url","domain":"github.com"}}}]"""
        const val DELIVERING_EDGES = """[{"from":"build","to":null,"on":"delivered"}]"""
        const val SEGMENT = """{"id":"s-1","agent":"claude-code","repo":"acme/api"}"""
        const val GATE_ONLY = """[{"id":"review","kind":"gate","label":"Review","approvers":["bea"]}]"""
    }
}
